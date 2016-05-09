package com.loadbalance;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import com.loadbalance.LoadBalancerHandler.Host;
import com.loadbalance.LoadBalancerHandler.HostSelector;

class RandomHostSelector implements HostSelector {

	Random rand;

	public RandomHostSelector() {
		rand = new Random();
	}

	public int selectHost(Host[] availableHosts) {
		return selectHost(availableHosts, null);
	}

	public int selectHost(Host[] availableHosts, String requestType) {
		return rand.nextInt(availableHosts.length);
	}
}
