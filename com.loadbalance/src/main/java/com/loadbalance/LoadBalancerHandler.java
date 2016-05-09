package com.loadbalance;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.ssl.XnioSsl;

import io.undertow.UndertowLogger;
import io.undertow.client.ClientConnection;
import io.undertow.client.UndertowClient;
import io.undertow.server.ExchangeCompletionListener;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.server.ExchangeCompletionListener.NextListener;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.proxy.ConnectionPoolManager;
import io.undertow.server.handlers.proxy.ExclusivityChecker;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyClient.ProxyTarget;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.server.handlers.proxy.ProxyConnectionPool;
import io.undertow.server.handlers.proxy.ProxyConnectionPool.AvailabilityType;
import io.undertow.server.handlers.proxy.ConnectionPoolErrorHandler;
import io.undertow.util.AttachmentKey;
import io.undertow.util.AttachmentList;
import io.undertow.util.CopyOnWriteMap;

import static org.xnio.IoUtils.safeClose;

public class LoadBalancerHandler implements ProxyClient {

	/**
	 * The attachment key that is used to attach the proxy connection to the
	 * exchange.
	 * <p>
	 * This cannot be static as otherwise a connection from a different client
	 * could be re-used.
	 */
	private final AttachmentKey<ExclusiveConnectionHolder> exclusiveConnectionKey = AttachmentKey
			.create(ExclusiveConnectionHolder.class);

	private static final AttachmentKey<AttachmentList<Host>> ATTEMPTED_HOSTS = AttachmentKey.createList(Host.class);

	/**
	 * Time in seconds between retries for problem servers
	 */
	private volatile int problemServerRetry = 10;

	private final Set<String> sessionCookieNames = new CopyOnWriteArraySet<String>();

	/**
	 * The number of connections to create per thread
	 */
	private volatile int connectionsPerThread = 10;
	private volatile int maxQueueSize = 0;
	private volatile int softMaxConnectionsPerThread = 5;
	private volatile int ttl = -1;

	/**
	 * The hosts list.
	 */
	private volatile Host[] hosts = {};

	private final HostSelector hostSelector;
	private final UndertowClient client;

	private final Map<String, Host> routes = new CopyOnWriteMap<String, Host>();

	private static final ProxyTarget PROXY_TARGET = new ProxyTarget() {
	};

	private Logger logger;

	public LoadBalancerHandler() {
		this(UndertowClient.getInstance());
	}

	public LoadBalancerHandler(UndertowClient client) {
		this(client, null);
	}

	public LoadBalancerHandler(UndertowClient client, HostSelector hostSelector) {
		logger = LoggerFactory.getLogger(LoadBalancerHandler.class);
		this.client = client;
		sessionCookieNames.add("JSESSIONID");
		// Default Round Robin
		if (hostSelector == null) {
			this.hostSelector = new RoundRobinHostSelector();
		} else {
			this.hostSelector = hostSelector;
		}
	}

	public LoadBalancerHandler addSessionCookieName(final String sessionCookieName) {
		sessionCookieNames.add(sessionCookieName);
		return this;
	}

	public LoadBalancerHandler removeSessionCookieName(final String sessionCookieName) {
		sessionCookieNames.remove(sessionCookieName);
		return this;
	}

	public LoadBalancerHandler setProblemServerRetry(int problemServerRetry) {
		this.problemServerRetry = problemServerRetry;
		return this;
	}

	public int getProblemServerRetry() {
		return problemServerRetry;
	}

	public int getConnectionsPerThread() {
		return connectionsPerThread;
	}

	public LoadBalancerHandler setConnectionsPerThread(int connectionsPerThread) {
		this.connectionsPerThread = connectionsPerThread;
		return this;
	}

	public int getMaxQueueSize() {
		return maxQueueSize;
	}

	public LoadBalancerHandler setMaxQueueSize(int maxQueueSize) {
		this.maxQueueSize = maxQueueSize;
		return this;
	}

	public LoadBalancerHandler setTtl(int ttl) {
		this.ttl = ttl;
		return this;
	}

	public LoadBalancerHandler setSoftMaxConnectionsPerThread(int softMaxConnectionsPerThread) {
		this.softMaxConnectionsPerThread = softMaxConnectionsPerThread;
		return this;
	}

	public synchronized LoadBalancerHandler addHost(final URI host) {
		return addHost(host, null);
	}

	public synchronized LoadBalancerHandler addHost(final URI host, String jvmRoute, String ami, String type,
			String instanceId) {
		Host h = new Host(jvmRoute, null, host, ami, type, instanceId, OptionMap.EMPTY);
		Host[] existing = hosts;
		Host[] newHosts = new Host[existing.length + 1];
		System.arraycopy(existing, 0, newHosts, 0, existing.length);
		newHosts[existing.length] = h;
		this.hosts = newHosts;
		if (jvmRoute != null) {
			this.routes.put(jvmRoute, h);
		}
		return this;
	}

	public synchronized LoadBalancerHandler addHost(final URI host, String jvmRoute, OptionMap options) {
		return addHost(null, host, jvmRoute, options);
	}

	public synchronized LoadBalancerHandler addHost(final URI host, String jvmRoute) {

		Host h = new Host(jvmRoute, null, host, OptionMap.EMPTY);
		Host[] existing = hosts;
		Host[] newHosts = new Host[existing.length + 1];
		System.arraycopy(existing, 0, newHosts, 0, existing.length);
		newHosts[existing.length] = h;
		this.hosts = newHosts;
		if (jvmRoute != null) {
			this.routes.put(jvmRoute, h);
		}
		return this;
	}

	public synchronized LoadBalancerHandler addHost(final InetSocketAddress bindAddress, final URI host,
			String jvmRoute, OptionMap options) {
		Host h = new Host(jvmRoute, bindAddress, host, options);
		Host[] existing = hosts;
		Host[] newHosts = new Host[existing.length + 1];
		System.arraycopy(existing, 0, newHosts, 0, existing.length);
		newHosts[existing.length] = h;
		this.hosts = newHosts;
		if (jvmRoute != null) {
			this.routes.put(jvmRoute, h);
		}
		return this;
	}

	public synchronized LoadBalancerHandler removeHost(final URI uri, String instanceId) {
		int found = -1;
		Host[] existing = hosts;
		Host removedHost = null;
		if (instanceId == null) {
			for (int i = 0; i < existing.length; ++i) {
				if (existing[i].uri.equals(uri)) {
					found = i;
					removedHost = existing[i];
					logger.info("Removing host by url " + uri.toString());
					break;
				}
			}
		} else {
			for (int i = 0; i < existing.length; ++i) {
				if (existing[i].instanceId.equals(instanceId)) {
					found = i;
					removedHost = existing[i];
					logger.info("Removing host by instanceid " + instanceId);
					break;
				}
			}
		}
		if (found == -1) {
			return this;
		}
		Host[] newHosts = new Host[existing.length - 1];
		System.arraycopy(existing, 0, newHosts, 0, found);
		System.arraycopy(existing, found + 1, newHosts, found, existing.length - found - 1);
		this.hosts = newHosts;
		removedHost.connectionPool.close();
		if (removedHost.jvmRoute != null) {
			routes.remove(removedHost.jvmRoute);
		}
		return this;
	}

	@Override
	public ProxyTarget findTarget(HttpServerExchange exchange) {
		return PROXY_TARGET;
	}

	@Override
	public void getConnection(ProxyTarget target, HttpServerExchange exchange, ProxyCallback<ProxyConnection> callback,
			long timeout, TimeUnit timeUnit) {
		exchange.addExchangeCompleteListener(new ExchangeCompletionListener() {

			public void exchangeEvent(HttpServerExchange exchange, NextListener next) {
				Map<String, Deque<String>> params = exchange.getQueryParameters();
				String type, req_id, argument;
				try {
					type = params.get("type").getLast();
					req_id = params.get("id").getLast();
					argument = params.get("arg").getLast();
				} catch (Exception e) {
					// Ignore Exception
					next.proceed();
					return;
				}

				StringBuilder builder = new StringBuilder();
				builder.append(type);
				builder.append(" ");
				builder.append(argument);
				builder.append(" REQID ");
				builder.append(req_id);
				builder.append(" ");
				builder.append((System.nanoTime() - exchange.getRequestStartTime()) / 1000000);
				builder.append("ms");
				builder.append(" ");
				builder.append(exchange.getStatusCode());
				// logger.info(builder.toString());
				next.proceed();
			}
		});
		final ExclusiveConnectionHolder holder = exchange.getConnection().getAttachment(exclusiveConnectionKey);
		if (holder != null && holder.connection.getConnection().isOpen()) {
			logger.debug("Exclusive Already ");
			// Something has already caused an exclusive connection to be
			// allocated so keep using it.
			callback.completed(exchange, holder.connection);
			return;
		}

		final Host host = selectHost(exchange);
		if (host == null) {
			callback.couldNotResolveBackend(exchange);
		} else {
			exchange.addToAttachmentList(ATTEMPTED_HOSTS, host);

			logger.debug("Non Exclusivity connecting ");
			host.connectionPool.connect(target, exchange, callback, timeout, timeUnit, false);

		}
	}

	protected Host selectHost(HttpServerExchange exchange) {
		AttachmentList<Host> attempted = exchange.getAttachment(ATTEMPTED_HOSTS);
		Host[] hosts = this.hosts;
		if (hosts.length == 0) {
			return null;
		}
		Host sticky = findStickyHost(exchange);
		if (sticky != null) {
			if (attempted == null || !attempted.contains(sticky)) {
				return sticky;
			}
		}
		Map<String, Deque<String>> params = exchange.getQueryParameters();
		String type = null;
		try {
			type = params.get("type").getLast();
		} catch (Exception e) {
			// Ignore Exception
		}
		int host = hostSelector.selectHost(hosts, type);

		final int startHost = host; // if the all hosts have problems we come
									// back to this one
		Host full = null;
		Host problem = null;
		ProxyConnectionPool.AvailabilityType available = null;
		do {
			Host selected = hosts[host];
			if (attempted == null || !attempted.contains(selected)) {
				available = selected.connectionPool.available();
				if (available == AvailabilityType.AVAILABLE) {
					return selected;
				} else if (available == AvailabilityType.FULL && full == null) {
					full = selected;
				} else if ((available == AvailabilityType.PROBLEM || available == AvailabilityType.FULL_QUEUE)
						&& problem == null) {
					problem = selected;
				}
			}
			host = (host + 1) % hosts.length;
		} while (host != startHost);
		if (full != null) {
			logger.debug("Full Host Selected " + available);
			return full;
		}
		if (problem != null) {
			logger.debug("Problem Host Selected " + available);
			return problem;
		}
		// no available hosts
		return null;
	}

	protected Host findStickyHost(HttpServerExchange exchange) {
		Map<String, Cookie> cookies = exchange.getRequestCookies();
		for (String cookieName : sessionCookieNames) {
			Cookie sk = cookies.get(cookieName);
			if (sk != null) {
				int index = sk.getValue().indexOf('.');

				if (index == -1) {
					continue;
				}
				String route = sk.getValue().substring(index + 1);
				index = route.indexOf('.');
				if (index != -1) {
					route = route.substring(0, index);
				}
				return routes.get(route);
			}
		}
		return null;
	}

	public final class Host extends ConnectionPoolErrorHandler.SimpleConnectionPoolErrorHandler
			implements ConnectionPoolManager {
		final ProxyConnectionPool connectionPool;
		final URI uri;
		final String jvmRoute, type, ami, instanceId;

		private Host(String jvmRoute, InetSocketAddress bindAddress, URI uri, OptionMap options) {
			this(jvmRoute, bindAddress, uri, null, null, null, options);
		}

		private Host(String jvmRoute, InetSocketAddress bindAddress, URI uri, String ami, String type,
				String instanceId, OptionMap options) {
			this.connectionPool = new ProxyConnectionPool(this, bindAddress, uri, null, client, options);
			this.uri = uri;
			this.jvmRoute = jvmRoute;
			this.ami = ami;
			this.type = type;
			this.instanceId = instanceId;
		}

		@Override
		public int getProblemServerRetry() {
			return problemServerRetry;
		}

		@Override
		public int getMaxConnections() {
			return connectionsPerThread;
		}

		@Override
		public int getMaxCachedConnections() {
			return connectionsPerThread;
		}

		@Override
		public int getSMaxConnections() {
			return softMaxConnectionsPerThread;
		}

		@Override
		public long getTtl() {
			return ttl;
		}

		@Override
		public int getMaxQueueSize() {
			return maxQueueSize;
		}

		public URI getUri() {
			return uri;
		}

		public int getCurrentConnections() {
			return connectionPool.getOpenConnections();
		}
	}

	private static class ExclusiveConnectionHolder {

		private ProxyConnection connection;

	}

	public interface HostSelector {

		int selectHost(Host[] availableHosts);

		int selectHost(Host[] availableHosts, String requestType);
	}

}
