package com.thing.item.service;

import com.thing.item.domain.Item;
import com.thing.item.dto.ItemDetailResponseDTO;
import com.thing.item.dto.ItemSaveRequestDTO;
import com.thing.item.dto.ItemSearchRequestDTO;
import com.thing.item.dto.ItemSimpleResponseDTO;
import org.springframework.data.domain.Slice;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface ItemService {

    public Item saveItem(ItemSaveRequestDTO itemSaveRequestDTO, List<MultipartFile> itemPhotos);
    public ItemDetailResponseDTO findItemOne(Integer itemId, Integer clientIndex);
    public Slice<ItemSimpleResponseDTO> findItemList(ItemSearchRequestDTO itemSearchRequestDTO, Integer clientIndex);
    public Slice<ItemSimpleResponseDTO> findItemListByOwnerIndex(Integer clientIndex, int page);
    public void deleteItem(Integer itemId, Integer clientIndex);
    public void modifyItem(Integer clientIndex, Integer itemId, ItemSaveRequestDTO itemSaveRequestDTO, List<MultipartFile> itemPhotoSaveRequest) throws IOException;
    public String getItemPhotoPath(Integer itemPhotoIndex);
    public List<ItemSimpleResponseDTO> findItemListByBasket(Integer clientIdx);

}
