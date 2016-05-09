package com.loadbalance;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import com.loadbalance.LoadBalancerHandler.Host;
import com.loadbalance.LoadBalancerHandler.HostSelector;

import io.undertow.server.handlers.proxy.ProxyConnectionPool.AvailabilityType;

class AwsScalingHostSelector implements HostSelector {

    private final AtomicInteger currentHost1,currentHost2;
    private final String COMPUTE_PRIME = "countprimes";
	private final String WISDOM_418 = "418wisdom";
	Random rand;
    
    public AwsScalingHostSelector() {
		currentHost1 = new AtomicInteger(0);
		currentHost2 = new AtomicInteger(0);
		rand = new Random(); 
	}

    public int selectHost(Host[] availableHosts) {
        return selectHost(availableHosts,null);
    }

    public int selectHost(Host[] availableHosts, String requestType) {
    	if (requestType == null) {
			LoadBalancer.logger.info("Type null request");
			return rand.nextInt(availableHosts.length);
		}
    	int startHost,host;
    	switch (requestType) {
		case WISDOM_418:
			startHost = currentHost1.get();
			host = (startHost + 1)% availableHosts.length;;
			do {
				if(availableHosts[host].type.contentEquals("c4.xlarge")){
					currentHost1.set(host);
					return host;
				}
				host = (host + 1) % availableHosts.length;
			} while (host != startHost);
			return rand.nextInt(availableHosts.length);
		case COMPUTE_PRIME:
			startHost = currentHost2.get();
			host = (startHost + 1)% availableHosts.length;;
			do {
				if(availableHosts[host].type.contentEquals("m4.large")){
					currentHost2.set(host);
					return host;
				}
				host = (host + 1) % availableHosts.length;
			} while (host != startHost);
			return rand.nextInt(availableHosts.length);
		default:
			return rand.nextInt(availableHosts.length);
		}
	}
}
