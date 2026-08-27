package com.macrotel.rapidstylers.pojo;

import lombok.Data;

@Data
public class SearchNearbyData {
    private double lat;
    private double lng;
    private double radius = 25;
    private String serviceTypeId;
    private String city;
    private String requestedDate;
    private String requestedTime;
    private int durationMinutes = 60;
    private boolean openNow = false;
}
