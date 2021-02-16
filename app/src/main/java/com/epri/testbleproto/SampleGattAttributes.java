package com.epri.testbleproto;

import java.util.HashMap;
import java.util.UUID;

public class SampleGattAttributes {
    private static HashMap<String, String> attributes = new HashMap();

    public static String TUART_SERVICE = "49535343-fe7d-4ae5-8fa9-9fafd205e455";
    public static String TUART_RX_CHARAC = "49535343-8841-43f4-a8d4-ecbe34729bb3";
    public static String TUART_TX_CHARAC = "49535343-1e4d-4bd9-ba61-23c647249616";

    public static UUID TUART_RX_UUID = UUID.fromString(TUART_RX_CHARAC);
    public static UUID TUART_TX_UUID = UUID.fromString(TUART_TX_CHARAC);



    public static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    //public static final UUID CCCD_UUID = UUID.fromString("2902");


    static {
        attributes.put(TUART_TX_CHARAC, "TUART Tx Charac");
        attributes.put(TUART_RX_CHARAC, "TUART Rx Charac");
    }

    public static String lookup(String uuid, String defaultName) {
        String name = attributes.get(uuid);
        return name == null ? defaultName : name;
    }

}
