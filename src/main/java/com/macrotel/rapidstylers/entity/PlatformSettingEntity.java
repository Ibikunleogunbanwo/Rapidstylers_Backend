package com.macrotel.rapidstylers.entity;

import lombok.Data;

import javax.persistence.*;
import java.io.Serializable;

/**
 * Runtime-configurable platform settings (key/value). Commission percent is
 * stored here so admins can change it without a restart; the value in
 * application.properties/.env is only the seed for a fresh database.
 */
@Data
@Entity
@Table(name = "platform_settings")
public class PlatformSettingEntity implements Serializable {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    private String settingKey;
    private String settingValue;

    public PlatformSettingEntity() {
    }

    public PlatformSettingEntity(String settingKey, String settingValue) {
        this.settingKey = settingKey;
        this.settingValue = settingValue;
    }
}
