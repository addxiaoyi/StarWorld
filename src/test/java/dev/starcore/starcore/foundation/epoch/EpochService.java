package dev.starcore.starcore.foundation.epoch;
import java.time.Instant;
public final class EpochService {
    public record EpochSnapshot(boolean enabled,long epochNumber,Instant epochStart,Instant nextEpochStart){}
    public EpochSnapshot snapshot(){throw new UnsupportedOperationException();}
}
