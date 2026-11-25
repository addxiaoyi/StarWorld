package dev.starworld.integration;

import dev.starworld.integration.TransitPermitPort.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransitPermitCodecTest {
    @Test void snapshotRoundtripIsImmutableAndCorruptOrOversizedStateFailsClosed(){
        UUID nation=UUID.randomUUID();Permit permit=new Permit(new Terms("starworld:route","a".repeat(64),nation,UUID.randomUUID(),UUID.randomUUID(),Set.of(new Parcel(UUID.randomUUID(),"中文世界",UUID.randomUUID(),-2,10)),new BigDecimal("0.03"),1000),3,false,UUID.randomUUID(),100);
        String encoded=TransitPermitCodec.encode(Map.of(nation,permit));var decoded=TransitPermitCodec.decode(encoded);assertEquals(Map.of(nation,permit),decoded);assertEquals(encoded,TransitPermitCodec.encode(decoded));assertThrows(UnsupportedOperationException.class,decoded::clear);
        assertEquals(Map.of(),TransitPermitCodec.decode(""));byte[] bytes=Base64.getDecoder().decode(encoded);bytes[3]=2;
        for(String invalid:List.of(encoded.substring(0,encoded.length()-8),encoded+"AAAA",Base64.getEncoder().encodeToString(bytes),"a".repeat(700_000),"!not-base64"))assertThrows(IllegalArgumentException.class,()->TransitPermitCodec.decode(invalid));
        assertThrows(IllegalArgumentException.class,()->TransitPermitCodec.encode(Map.of(UUID.randomUUID(),permit)));
    }
}
