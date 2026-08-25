package com.macrotel.rapidstylers.pojo;

import lombok.Data;

@Data
public class GalleryData {
    private String category;
    private String perPage;   // optional, defaults to 12
}
