package dev.starworld.integration;

import java.time.Instant;
import java.util.*;

/** Immutable boundary: every implementation call belongs on the Paper server thread. */
public interface CoreExtensionPort {
    Optional<NationSnapshot> snapshot(UUID nationId, Set<UUID> diplomacyPeers);
    Delivery deliver(ResourceRequest request);
    Optional<ResourceReceipt> receipt(String transactionId);

    record Epoch(boolean enabled, long number, Instant start, Instant nextStart) {}
    record ProcessingRecipe(String id, Map<String,Long> inputs, Map<String,Long> outputs, long seconds) {
        public ProcessingRecipe { inputs=Map.copyOf(inputs); outputs=Map.copyOf(outputs); }
    }
    record NationSnapshot(UUID nationId, String name, Instant capturedAt, String weather,
                          Map<String,Double> weatherResourceMultipliers, Epoch epoch,
                          Set<String> technologies, Map<String,Double> technologyModifiers,
                          String policy, Map<String,Double> policyModifiers,
                          Map<String,Long> resourceStock, double prosperity,
                          Set<UUID> warOpponents, Map<UUID,String> diplomacy,
                          List<ProcessingRecipe> processingRecipes, int factoryCount,
                          Map<String,String> capabilities) {
        public NationSnapshot {
            Objects.requireNonNull(nationId); Objects.requireNonNull(name); Objects.requireNonNull(capturedAt);
            weatherResourceMultipliers=Map.copyOf(weatherResourceMultipliers); technologies=Set.copyOf(technologies);
            technologyModifiers=Map.copyOf(technologyModifiers); policyModifiers=Map.copyOf(policyModifiers);
            resourceStock=Map.copyOf(resourceStock); warOpponents=Set.copyOf(warOpponents); diplomacy=Map.copyOf(diplomacy);
            processingRecipes=List.copyOf(processingRecipes); capabilities=Map.copyOf(capabilities);
        }
    }
    record ResourceRequest(String transactionId, UUID nationId, Map<String,Long> resources, String description) {
        public ResourceRequest {
            if(transactionId==null || transactionId.isBlank() || transactionId.length()>200)throw new IllegalArgumentException("Invalid transaction id");
            Objects.requireNonNull(nationId); resources=resourceAmounts(resources);
            description=description==null?"":description.substring(0,Math.min(200,description.length()));
        }
    }
    record ResourceReceipt(String transactionId, UUID nationId, Map<String,Long> resources,
                           String operation, String category, Instant appliedAt) {
        public ResourceReceipt { resources=resourceAmounts(resources);Objects.requireNonNull(appliedAt); }
        public boolean matches(ResourceRequest request) {
            return transactionId.equals(request.transactionId()) && nationId.equals(request.nationId())
                    && resources.equals(request.resources()) && operation.equals("GRANT") && category.equals("OTHER");
        }
    }
    enum DeliveryStatus { CONFIRMED, RETRY, REJECTED, CONFLICT }
    record Delivery(String transactionId, DeliveryStatus status, ResourceReceipt receipt, String detail) {
        public Delivery { Objects.requireNonNull(transactionId);Objects.requireNonNull(status);detail=detail==null?"":detail.substring(0,Math.min(1000,detail.length())); }
    }
    static Map<String,Long> resourceAmounts(Map<String,Long> source) {
        Objects.requireNonNull(source);if(source.isEmpty() || source.size()>64)throw new IllegalArgumentException("Invalid resource count");
        TreeMap<String,Long> result=new TreeMap<>();source.forEach((key,value)->{
            if(key==null || !key.matches("[a-z][a-z0-9_]{0,47}") || value==null || value<1 || value>1_000_000_000L)throw new IllegalArgumentException("Invalid resource amount");
            result.put(key,value);
        });return Collections.unmodifiableMap(result);
    }
}
