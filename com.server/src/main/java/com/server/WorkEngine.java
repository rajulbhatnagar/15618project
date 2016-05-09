package com.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.spy.memcached.AddrUtil;
import net.spy.memcached.ConnectionFactoryBuilder;
import net.spy.memcached.MemcachedClient;
import net.spy.memcached.transcoders.SerializingTranscoder;

public class WorkEngine {
	private final String basePath = "/home/ubuntu/images/wall/";
	private final String seperator = "\t";
	private MemcachedClient memcache;

	Logger logger;

	final String motivation[] = { "You are going to do a great project", "OMG, 418 is so gr8!",
			"Come to lecture, there might be donuts!",
			"Write a great lecture comment on your favorite idea in the class",
			"Bring out all the stops in assignment 4.", "Ask questions. Ask questions. Ask questions",
			"Flatter your TAs with compliments", "Worse is better. Keep it simple...",
			"You will perform amazingly on exam 2", "You will PWN your classmates in the parallelism competition",
			"Exams are all just fun and games", "Do as best as you can and just have fun!", "Laugh at Kayvon's jokes",
			"Do a great project, and it all works out in the end", "Be careful not to optimize prematurely",
			"If all else fails... buy Kayvon donuts" };

	final String responses[] = { "We recommend getting full credit on grading_wisdom.txt first",
			"Re-watch the lecture on scaling a website",
			"There are two of these: http://ark.intel.com/products/83352/Intel-Xeon-Processor-E5-2620-v3-15M-Cache-2_40-GHz",
			"You need good perf out of a node AND the ability to scale-out",
			"Yes, you can optimize for specific traces but you don't have to. A general schedule algorithm works.",
			"Figure out a way to understand the workload characteristics in each trace.",
			"There may be opportunities for caching in this assignment",
			"Are there any other opportunities for parallelism? (other than parallelism across requests?)",
			"The costs of communication between server nodes is likely not significant in this assignment.",
			"The best performance may come from a particular mixture of jobs on a worker node." };

	public WorkEngine() throws IOException {
		logger = LoggerFactory.getLogger(WorkEngine.class);
		SerializingTranscoder transcoder = new SerializingTranscoder(30 * 1024 * 1024);
		transcoder.setCompressionThreshold(Integer.MAX_VALUE);
		memcache = new MemcachedClient(
				new ConnectionFactoryBuilder().setTranscoder(transcoder).setOpTimeout(10000).build(),
				AddrUtil.getAddresses("127.0.0.1:11211"));

	}

	String high_compute_job(int x, String id) {
		long start = System.nanoTime();

		int iters = 125 * 1000 * 1000;
		Random rand;
		int val = 0;

		for (int i = 0; i < iters; i++) {
			val = new Random(x).nextInt(Integer.MAX_VALUE);
		}

		int idx = val % 16;
		String resp = motivation[idx] + seperator + id;
		long end = System.nanoTime();
		StringBuilder builder = new StringBuilder();
		builder.append("WISDOM418 ");
		builder.append(x);
		builder.append(" REQID ");
		builder.append(id);
		builder.append(" RESPONSE ");
		builder.append(resp);
		builder.append((end - start) / 1000000);
		builder.append("ms");
		logger.info(builder.toString());
		return resp;
	}

	String high_memory_job(String x, String id) throws IOException {
		long start = System.nanoTime();
		String resp = null;
		byte[] image = (byte[]) memcache.get(x);
		if (image == null) {
			Path path = Paths.get(basePath + x);
			image = Files.readAllBytes(path);
			memcache.add(x, 900, image);
		}

		resp = "Size of File " + x + " :" + image.length + seperator + id;
		image = null;
		long end = System.nanoTime();
		StringBuilder builder = new StringBuilder();
		builder.append("MEMORYKILLER ");
		builder.append(x);
		builder.append(" REQID ");
		builder.append(id);
		builder.append(" RESPONSE ");
		builder.append(resp);
		builder.append((end - start) / 1000000);
		builder.append("ms");
		logger.info(builder.toString());
		return resp;
	}

	String mini_compute_job(int x, String id) {
		long start = System.nanoTime();

		// result = x * x + 10
		int result = x * x + 10;
		int idx = Math.abs(result) % 10;
		String resp;

		resp = responses[idx] + seperator + id;
		long end = System.nanoTime();
		StringBuilder builder = new StringBuilder();
		builder.append("TELLMENOW ");
		builder.append(x);
		builder.append(" REQID ");
		builder.append(id);
		builder.append(" RESPONSE ");
		builder.append(resp);
		builder.append((end - start) / 1000000);
		builder.append("ms");
		logger.info(builder.toString());
		return resp;
	}

	@SuppressWarnings("unused")
	String high_bandwidth_job(int x, String id) {

		long start = System.nanoTime();
		int NUM_ITERS = 100;
		int ALLOCATION_SIZE = 64 * 1000 * 1000;
		int NUM_ELEMENTS = ALLOCATION_SIZE / Integer.BYTES;
		String response;

		// Allocate a buffer that's much larger than the LLC and populate
		// it.
		int[] buffer = new int[NUM_ELEMENTS];
		if (buffer == null) {
			// worth checking for
			response = "allocation failed: worker likely out of memory";
			return response;
		}

		for (int i = 0; i < NUM_ELEMENTS; i++) {
			buffer[i] = i;
		}

		int index = x % NUM_ELEMENTS;
		int total = 0;

		// double startTime = CycleTimer::currentSeconds();

		// loop over the buffer, jumping by a cache line each time. Simple
		// stride means the prefetcher will probably do reasonably well but
		// we'll be terribly bandwidth bound.
		for (int iter = 0; iter < NUM_ITERS; iter++) {
			for (int i = 0; i < NUM_ELEMENTS; i++) {
				total += buffer[index];
				index += 16;
				if (index >= NUM_ELEMENTS)
					index = 0;
			}
		}

		response = String.valueOf(total) + seperator + id;
		buffer = null;
		long end = System.nanoTime();
		StringBuilder builder = new StringBuilder();
		builder.append("BANDWITH ");
		builder.append(x);
		builder.append(" REQID ");
		builder.append(id);
		builder.append(" RESPONSE ");
		builder.append(response);
		builder.append((end - start) / 1000000);
		builder.append("ms");
		logger.info(builder.toString());
		return response;
	}

	String count_primes_job(int x, String id) {
		long start = System.nanoTime();
		String response;
		int N = x;

		int NUM_ITER = 10;
		int count = 0;

		for (int iter = 0; iter < NUM_ITER; iter++) {
			count = (N >= 2) ? 1 : 0; // since 2 is prime

			for (int i = 3; i < N; i += 2) { // For every odd number

				int prime;
				int div1, div2, rem;

				prime = i;

				// Keep searching for divisor until rem == 0 (i.e. non prime),
				// or we've reached the sqrt of prime (when div1 > div2)

				div1 = 1;
				do {
					div1 += 2; // Divide by 3, 5, 7, ...
					div2 = prime / div1; // Find the dividend
					rem = prime % div1; // Find remainder
				} while (rem != 0 && div1 <= div2);

				if (rem != 0 || div1 == prime) {
					// prime is really a prime
					count++;
				}
			}
		}

		response = String.valueOf(count) + seperator + id;
		long end = System.nanoTime();
		StringBuilder builder = new StringBuilder();
		builder.append("COUNTPRIME ");
		builder.append(x);
		builder.append(" REQID ");
		builder.append(id);
		builder.append(" RESPONSE ");
		builder.append(response);
		builder.append((end - start) / 1000000);
		builder.append("ms");
		logger.info(builder.toString());
		return response;
	}
}
