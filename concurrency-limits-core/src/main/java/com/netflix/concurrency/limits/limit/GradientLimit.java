package com.netflix.concurrency.limits.limit;

import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.MetricIds;
import com.netflix.concurrency.limits.MetricRegistry;
import com.netflix.concurrency.limits.internal.EmptyMetricRegistry;
import com.netflix.concurrency.limits.internal.Preconditions;
import com.netflix.concurrency.limits.limit.functions.SquareRootFunction;

import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Concurrency limit algorithm that adjust the limits based on the gradient of change in the 
 * samples minimum RTT and absolute minimum RTT allowing for a queue of square root of the 
 * current limit.  Why square root?  Because it's better than a fixed queue size that becomes too
 * small for large limits but still prevents the limit from growing too much by slowing down
 * growth as the limit grows.
 */
public final class GradientLimit implements Limit {
    private static final Logger LOG = LoggerFactory.getLogger(GradientLimit.class);
    
    public static class Builder {
        private int initialLimit = 100;
        private int maxConcurrency = 1000;
        private long minRttThreshold = TimeUnit.MICROSECONDS.toNanos(200);
        
        private double smoothing = 0.2;
        private Function<Integer, Integer> queueSize = SquareRootFunction.create(4);
        private MetricRegistry registry = EmptyMetricRegistry.INSTANCE;
        private double rttTolerance = 1.0;
        
        /**
         * Minimum threshold for accepting a new rtt sample.  Any RTT lower than this threshold
         * will be discarded.
         *  
         * @param minRttTreshold
         * @param units
         * @return Chainable builder
         */
        public Builder minRttThreshold(long minRttTreshold, TimeUnit units) {
            this.minRttThreshold = units.toNanos(minRttTreshold);
            return this;
        }
        
        /**
         * Initial limit used by the limiter
         * @param initialLimit
         * @return Chainable builder
         */
        public Builder initialLimit(int initialLimit) {
            this.initialLimit = initialLimit;
            return this;
        }
        
        /**
         * Tolerance for changes in minimum latency.  
         * @param rttTolerance Value {@literal >}= 1.0 indicating how much change in minimum latency is acceptable
         *  before reducing the limit.  For example, a value of 2.0 means that a 2x increase in latency is acceptable. 
         * @return Chainable builder
         */
        public Builder rttTolerance(double rttTolerance) {
            Preconditions.checkArgument(rttTolerance >= 1.0, "Tolerance must be >= 1.0");
            this.rttTolerance = rttTolerance;
            return this;
        }
        
        /**
         * Maximum allowable concurrency.  Any estimated concurrency will be capped
         * at this value
         * @param maxConcurrency
         * @return Chainable builder
         */
        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }
        
        /**
         * Fixed amount the estimated limit can grow while latencies remain low
         * @param queueSize
         * @return Chainable builder
         */
        public Builder queueSize(int queueSize) {
            this.queueSize = (ignore) -> queueSize;
            return this;
        }

        /**
         * Function to dynamically determine the amount the estimated limit can grow while
         * latencies remain low as a function of the current limit.
         * @param queueSize
         * @return Chainable builder
         */
        public Builder queueSize(Function<Integer, Integer> queueSize) {
            this.queueSize = queueSize;
            return this;
        }
        
        /**
         * Smoothing factor to limit how aggressively the estimated limit can shrink
         * when queuing has been detected.
         * @param smoothing Value of 0.0 to 1.0 where 1.0 means the limit is completely
         *  replicated by the new estimate.
         * @return Chainable builder
         */
        public Builder smoothing(double smoothing) {
            this.smoothing = smoothing;
            return this;
        }
        
        /**
         * Registry for reporting metrics about the limiter's internal state.
         * @param registry
         * @return Chainable builder
         */
        public Builder metricRegistry(MetricRegistry registry) {
            this.registry = registry;
            return this;
        }
        
        public GradientLimit build() {
            GradientLimit limit = new GradientLimit(this);
            registry.registerGauge(MetricIds.MIN_RTT_GUAGE_NAME, limit::getRttNoLoad);
            registry.registerGauge(MetricIds.WINDOW_MIN_RTT_GUAGE_NAME, limit::getWindowMinRtt);
            registry.registerGauge(MetricIds.WINDOW_QUEUE_SIZE_GUAGE_NAME, limit::getQueueSize);
            return limit;
        }
    }
    
    public static Builder newBuilder() {
        return new Builder();
    }
    
    public static GradientLimit newDefault() {
        return newBuilder().build();
    }
    
    /**
     * Estimated concurrency limit based on our algorithm
     */
    private volatile double estimatedLimit;
    
    private volatile long rtt_noload = 0;
    
    private volatile long windowMinRtt = 0;
    
    private boolean didDrop = false;
    
    /**
     * Maximum allowed limit providing an upper bound failsafe
     */
    private final int maxLimit; 
    
    private final Function<Integer, Integer> queueSize;
    
    private final double smoothing;

    private final long minRttThreshold;

    private final double rttTolerance; 
    
    private GradientLimit(Builder builder) {
        this.estimatedLimit = builder.initialLimit;
        this.maxLimit = builder.maxConcurrency;
        this.queueSize = builder.queueSize;
        this.smoothing = builder.smoothing;
        this.minRttThreshold = builder.minRttThreshold;
        this.rttTolerance = builder.rttTolerance;
    }

    @Override
    public synchronized void update(long rtt, int maxInFlight) {
        Preconditions.checkArgument(rtt > 0, "rtt must be >0 but got " + rtt);
        
        if (rtt < minRttThreshold) {
            return;
        }
        
        // Track the recent minimum for reporting purposes only
        if (windowMinRtt == 0 || rtt < windowMinRtt) {
            windowMinRtt = rtt;
        }
        
        if (rtt_noload == 0 || rtt < rtt_noload) {
            LOG.debug("New MinRTT {}", rtt);
            rtt_noload = rtt;
        }
        
        final double queueSize = this.queueSize.apply((int)this.estimatedLimit);
        final double gradient = Math.max(0.5, Math.min(1.0, rttTolerance * rtt_noload / rtt));
        double newLimit;
        if (didDrop) {
            newLimit = estimatedLimit/2;
            didDrop = false;
        } else if ((estimatedLimit - maxInFlight) > queueSize) {
            return;
        } else {
            newLimit = estimatedLimit * gradient + queueSize;
        }
        
        newLimit = Math.max(queueSize, Math.min(maxLimit, newLimit));
        if (newLimit < estimatedLimit) {
            newLimit = estimatedLimit * (1-smoothing) + smoothing*(newLimit);
        }
        if ((int)newLimit != (int)estimatedLimit) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("New limit={} minRtt={} μs winRtt={} μs queueSize={} gradient={}", 
                        (int)estimatedLimit, 
                        TimeUnit.NANOSECONDS.toMicros(rtt_noload), 
                        TimeUnit.NANOSECONDS.toMicros(rtt),
                        queueSize,
                        gradient);
            }
        }
        estimatedLimit = newLimit;
    }

    @Override
    public synchronized void drop() {
        didDrop = true;
    }

    @Override
    public int getLimit() {
        return (int)estimatedLimit;
    }

    public long getRttNoLoad() {
        return rtt_noload;
    }
    
    /**
     * Minimum RTT since last reported gauge value.  This is used solely 
     * for reporting purposes and does not factor into any calculation
     */
    private synchronized long getWindowMinRtt() {
        try {
            return this.windowMinRtt;
        } finally {
            windowMinRtt = 0;
        }
    }
    
    private synchronized long getQueueSize() {
        return this.queueSize.apply((int)this.estimatedLimit);
    }
    
    @Override
    public String toString() {
        return "GradientLimit [limit=" + (int)estimatedLimit + 
                ", rtt_noload=" + TimeUnit.NANOSECONDS.toMillis(rtt_noload) +
                "]";
    }
}
