package com.macrotel.rapidstylers.styler.pojo;

import javax.validation.constraints.Size;
import lombok.Data;

/**
 * A stylist replacing their profile photo, cover photo, or both. Each URL must
 * be a Cloudinary upload made with this stylist's own upload signature. A field
 * left out keeps the current photo.
 */
@Data
public class StylerPhotosData {
    @Size(max = 500, message = "Image URL is too long")
    private String profileImageUrl;
    @Size(max = 500, message = "Image URL is too long")
    private String coverImageUrl;
}
