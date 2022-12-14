package com.thing.item.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.thing.item.client.BasketServiceFeignClient;
import com.thing.item.client.ClientServiceFeignClient;
import com.thing.item.domain.ElasticItem;
import com.thing.item.domain.Item;
import com.thing.item.domain.ItemPhoto;
import com.thing.item.dto.*;
import com.thing.item.exception.ItemNotFoundException;
import com.thing.item.exception.ItemPhotoSaveFailException;
import com.thing.item.exception.KakaoMapErrorException;
import com.thing.item.exception.MisMatchOwnerException;
import com.thing.item.repository.ElasticItemRepository;
import com.thing.item.repository.ItemPhotoRepository;
import com.thing.item.repository.ItemRepository;
import com.thing.item.repository.ItemRepositoryCustom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.geo.Point;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ItemServiceImpl implements ItemService{

    private final ItemRepository itemRepository;
    private final ItemPhotoRepository itemPhotoRepository;
    private final ClientServiceFeignClient clientServiceFeignClient;
    private final BasketServiceFeignClient basketServiceFeignClient;
    private final ItemRepositoryCustom itemRepositoryCustom;
    private final ElasticItemRepository elasticItemRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CircuitBreakerFactory circuitBreakerFactory;

    @Value("${kakao_api_key}")
    private String KAKAO_API_KEY;
    @Value("${image.path.item}")
    private String IMAGE_ROOT_PATH;

    @Transactional
    @Override
    public Item saveItem(ItemSaveRequestDTO itemSaveRequestDTO, List<MultipartFile> itemPhotoSaveRequest) {
        Item item = itemSaveRequestDTO.toEntity();
        Point addressPoint = getAddressPoint(item.getItemAddress());
        item.setPoint(addressPoint);
        item = itemRepository.save(item);
        // ?????? ??????
        savePhotos(item.getItemId(), itemPhotoSaveRequest);
        return item;
    }

    @Override
    public ItemDetailResponseDTO findItemOne(Integer itemId, Integer clientIndex) {
        Item item = itemRepository.findById(itemId).orElseThrow(ItemNotFoundException::new);
        item.addView();
        itemRepository.save(item);
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("circuitbreaker");
        ClientInfoDTO clientInfoDTO = circuitBreaker.run(() -> clientServiceFeignClient.getClient(item.getOwnerId()).getData(),
                throwable -> {
                    log.error(throwable.getCause() + " " + throwable.getMessage());
                    return new ClientInfoDTO();
                });
        // ???????????? ??? ?????? ?????????
        Integer basketCount = circuitBreaker.run(() -> basketServiceFeignClient.countBasket(itemId).getData(),
                throwable -> 0);
        // ?????? ?????? ??? ?????? ??????
        boolean isLike = (clientIndex == -1)? false : circuitBreaker.run(() -> basketServiceFeignClient.showBasket(clientIndex, itemId).getData(),
                throwable -> false);
        return ItemDetailResponseDTO.from(clientInfoDTO, item, basketCount, isLike);
    }

    @Override
    public Slice<ItemSimpleResponseDTO> findItemList(ItemSearchRequestDTO itemSearchRequestDTO, Integer clientIndex) {
        Pageable pageable = PageRequest.of(itemSearchRequestDTO.getPage(), 10);
        List<ElasticItem> itemList = Collections.emptyList();
        if (StringUtils.hasText(itemSearchRequestDTO.getQuery())){
            itemList = elasticItemRepository.searchItemByQuery(itemSearchRequestDTO.getQuery());
        }
        List<ItemSimpleResponseDTO> content = itemRepositoryCustom.findByItemList(itemSearchRequestDTO, pageable, itemList);
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("circuitbreaker");
        // ???????????? ??????
        if(clientIndex != -1){
            for(ItemSimpleResponseDTO dto : content){
                dto.setIsLike(circuitBreaker.run(() -> basketServiceFeignClient.showBasket(clientIndex, dto.getItemId()).getData(),
                        throwable -> false));
            }
        }
        boolean hasNext = false;
        if (content.size() > pageable.getPageSize()) {
            content.remove(pageable.getPageSize());
            hasNext = true;
        }
        return new SliceImpl<>(content, pageable, hasNext);
    }

    @Override
    public Slice<ItemSimpleResponseDTO> findItemListByOwnerIndex(Integer clientIndex, int page) {
        return itemRepository.findByOwnerId(clientIndex, PageRequest.of(page, 10));
    }

    @Transactional
    @Override
    public void deleteItem(Integer itemId, Integer clientIndex) {
        Item item = itemRepository.findById(itemId).orElseThrow(ItemNotFoundException::new);
        if(!item.getOwnerId().equals(clientIndex))
            throw new MisMatchOwnerException();
        // ?????? ?????? ?????? ??????
        deletePhotos(itemId);

        itemRepository.delete(item);
    }

    @Transactional
    @Override
    public void modifyItem(Integer clientIndex, Integer itemId, ItemSaveRequestDTO itemSaveRequestDTO, List<MultipartFile> itemPhotoSaveRequest) throws IOException {
        Item item = itemRepository.findById(itemId).orElseThrow(ItemNotFoundException::new);
        if(!item.getOwnerId().equals(clientIndex))
            throw new MisMatchOwnerException();

        // ?????? ?????? ?????? ??????
        deletePhotos(itemId);

        Point point = getAddressPoint(itemSaveRequestDTO.getItemAddress());
        itemSaveRequestDTO.setItemLongitude(point.getX());
        itemSaveRequestDTO.setItemLatitude(point.getY());

        item.modifyItemInfo(itemSaveRequestDTO);
        itemRepository.save(item);
        
        itemPhotoRepository.deleteAll(item.getPhotos());
        // ?????? ?????? ??????
        savePhotos(itemId, itemPhotoSaveRequest);
    }

    @Override
    public String getItemPhotoPath(Integer itemPhotoIndex) {
        ItemPhoto itemPhoto = itemPhotoRepository.findById(itemPhotoIndex).orElseThrow();
        return itemPhoto.getItemPhoto();
    }

    @Override
    public List<ItemSimpleResponseDTO> findItemListByBasket(Integer clientIdx) {
        CircuitBreaker circuitBreaker = circuitBreakerFactory.create("circuitbreaker");
        List<Integer> itemIdList = circuitBreaker.run(() -> basketServiceFeignClient.showBasketList(clientIdx).getData(),
                throwable -> new ArrayList<>());
        List<ItemSimpleResponseDTO> itemList = itemRepository.findByItemIdIn(itemIdList);
        for(ItemSimpleResponseDTO item : itemList){
            item.setIsLike(true);
        }
        return itemList;
    }

    private void savePhotos(Integer itemId, List<MultipartFile> files) {
        List<ItemPhoto> photoList = new ArrayList<>();

        // ???????????? ??? ????????? ????????? ??????
        if (!CollectionUtils.isEmpty(files)) {
            // ???????????? ???????????? ?????? ????????? ?????? ?????? ?????? ??????
            // ?????? ????????? File.separator ??????
            // ????????? ????????? ?????? ?????? ??????
            String path = File.separator + itemId;
            File file = new File(IMAGE_ROOT_PATH + path);

            // ??????????????? ???????????? ?????? ??????
            if (!file.exists()) {
                boolean wasSuccessful = file.mkdirs();

                // ???????????? ????????? ???????????? ??????
                if (!wasSuccessful){
                    log.error("???????????? ?????? ??????");
                    throw new ItemPhotoSaveFailException();
                }
            }

            // ?????? ?????? ??????
            boolean isMain = true;
            try{
                for (MultipartFile multipartFile : files) {
                    // ????????? ????????? ??????
                    int position = multipartFile.getOriginalFilename().lastIndexOf(".");
                    String originalFileExtension = multipartFile.getOriginalFilename().substring(position);

                    // ??????????????? ???????????? ?????? ?????? ?????? x
                    if (ObjectUtils.isEmpty(originalFileExtension)) {
                        continue;
                    }

                    String fileName = UUID.randomUUID() + originalFileExtension;
                    ItemPhoto itemPhoto = ItemPhoto.builder()
                            .itemId(itemId)
                            .itemPhoto(IMAGE_ROOT_PATH + path + File.separator + fileName)
                            .isMain(isMain)
                            .build();

                    if (isMain) isMain = false;

                    // ?????? ??? ???????????? ??????
                    photoList.add(itemPhoto);

                    // ????????? ??? ?????? ???????????? ????????? ????????? ??????
                    file = new File(itemPhoto.getItemPhoto());
                    multipartFile.transferTo(file);

                    // ?????? ?????? ??????(??????, ??????)
                    file.setWritable(true);
                    file.setReadable(true);
                }
            }catch (IOException e){
                e.printStackTrace();
                throw new ItemPhotoSaveFailException();
            }
        }

        if (photoList.size() > 0) itemPhotoRepository.saveAll(photoList);
    }

    private void deletePhotos(Integer itemId){
        String path = IMAGE_ROOT_PATH + File.separator + itemId;
        File dir = new File(path);
        if(dir.exists()){
            File[] deleteList = dir.listFiles();

            for (int j = 0; j < deleteList.length; j++) {
                deleteList[j].delete();
            }

            if(deleteList.length == 0 && dir.isDirectory()){
                dir.delete();
            }
        }
    }

    private Point getAddressPoint(String address){
        URI uri = UriComponentsBuilder
                .fromUriString("https://dapi.kakao.com")
                .path("/v2/local/search/address.json")
                .queryParam("query", address)
                .queryParam("analyze_type", "similar")
                .encode()
                .build()
                .toUri();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.set("Authorization", "KakaoAK " + KAKAO_API_KEY);
        HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(null, headers);

        Point point = null;
        try{
            ResponseEntity<String> response = restTemplate.exchange(uri, HttpMethod.GET, request, String.class);
            KakaoAddress kakaoAddress = new Gson().fromJson(response.getBody(), KakaoAddress.class);
            if(kakaoAddress.getDocuments().size() > 0){
                Document doc = kakaoAddress.getDocuments().get(0);
                point = new Point(Double.parseDouble(doc.getX()), Double.parseDouble(doc.getY()));
            }
        }catch(Exception e){
            e.printStackTrace();
            throw new KakaoMapErrorException();
        }

        return point;
    }
}
