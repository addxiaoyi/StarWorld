package dev.starworld.api;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClimateReportTest {
    @Test void publicClimateProjectionIsImmutableAndDoesNotExposeBukkitTypes(){
        UUID building=UUID.randomUUID(),city=UUID.randomUUID(),nation=UUID.randomUUID();
        Map<String,Double> factors=new HashMap<>(Map.of("harvest",0.75));
        var exposure=new StarWorldApi.ClimateExposure(building,"farm","world","snow",true,true,factors);factors.put("harvest",0.1);
        var report=new StarWorldApi.ClimateReport(city,nation,new ArrayList<>(List.of(exposure)));
        assertEquals(0.75,report.exposures().getFirst().recipeFactors().get("harvest"));
        assertThrows(UnsupportedOperationException.class,()->report.exposures().clear());
        assertThrows(UnsupportedOperationException.class,()->report.exposures().getFirst().recipeFactors().put("harvest",1.0));
        assertThrows(IllegalArgumentException.class,()->new StarWorldApi.ClimateReport(city,nation,Collections.nCopies(65,exposure)));
    }
}
