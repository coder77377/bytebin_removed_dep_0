/*
 * This file is part of bytebin, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.bytebin;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Weigher;
import com.google.common.base.Preconditions;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.rapidoid.http.MediaType;
import org.rapidoid.http.Req;
import org.rapidoid.http.Resp;
import org.rapidoid.setup.Setup;
import org.rapidoid.u.U;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.text.DateFormat;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Stupidly simple "pastebin" service.
 */
public class Bytebin implements AutoCloseable {

    // Bootstrap
    public static void main(String[] args) throws Exception {
        // load config
        Path configPath = Paths.get("config.json");
        Configuration config;

        if (Files.exists(configPath)) {
            try (BufferedReader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
                config = new Configuration(new Gson().fromJson(reader, JsonObject.class));
            }
        } else {
            config = new Configuration(new JsonObject());
        }

        Bytebin bytebin = new Bytebin(config);
        Runtime.getRuntime().addShutdownHook(new Thread(bytebin::close, "Bytebin Shutdown Thread"));
    }

    /** Empty byte array */
    private static final byte[] EMPTY_BYTES = new byte[0];

    /** Empty content instance */
    private static final Content EMPTY_CONTENT = new Content(null, MediaType.TEXT_PLAIN, Long.MAX_VALUE, EMPTY_BYTES);

    /** Number of bytes in a megabyte */
    private static final long MEGABYTE_LENGTH = 1024L * 1024L;

    /** Logger instance */
    private final Logger logger;

    /** Executor service for performing file based i/o */
    private final ScheduledExecutorService executor;

    /** Executor service used for logging. */
    private final ExecutorService loggingExecutor;

    /** Content cache - caches the raw byte data for the last x requested files */
    private final AsyncLoadingCache<String, Content> contentCache;

    /** Post rate limiter cache */
    private final RateLimiter postRateLimiter;

    /** Read rate limiter */
    private final RateLimiter readRateLimiter;

    /** The max content length in mb */
    private final long maxContentLength;

    /** Instance responsible for loading data from the filesystem */
    private final ContentLoader loader;

    /** Token generator */
    private final TokenGenerator tokenGenerator;

    /** Index page */
    private final byte[] indexPage;

    // the path to store the content in
    private final Path contentPath;
    // the lifetime of content in milliseconds
    private final long lifetimeMillis;
    // web server host
    private final String host;
    // web server port
    private final int port;

    /** The web server instance */
    private final Setup server;

    public Bytebin(Configuration config) throws Exception {
        // setup simple logger
        this.logger = createLogger();
        this.logger.info("loading bytebin...");

        // setup executor
        this.executor = Executors.newScheduledThreadPool(
                config.getInt("corePoolSize", 16),
                new ThreadFactoryBuilder().setNameFormat("bytebin-io-%d").build()
        );
        this.loggingExecutor = Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setNameFormat("bytebin-logging-%d").build());

        // setup loader
        this.loader = new ContentLoader();

        // how many minutes to cache content for
        int cacheTimeMins = config.getInt("cacheExpiryMinutes", 10);

        // build content cache
        this.contentCache = Caffeine.newBuilder()
                .executor(this.executor)
                .expireAfterAccess(cacheTimeMins, TimeUnit.MINUTES)
                .maximumWeight(config.getInt("cacheMaxSizeMb", 200) * MEGABYTE_LENGTH)
                .weigher((Weigher<String, Content>) (path, content) -> content.content.length)
                .buildAsync(this.loader);

        // make a new token generator
        this.tokenGenerator = new TokenGenerator(config.getInt("keyLength", 7));

        // read other config settings
        this.contentPath = Paths.get("content");
        this.lifetimeMillis = TimeUnit.MINUTES.toMillis(config.getLong("lifetimeMinutes", TimeUnit.DAYS.toMinutes(1)));
        this.host = System.getProperty("server.host", config.getString("host", "127.0.0.1"));
        this.port = Integer.getInteger("server.port", config.getInt("port", 8080));
        this.maxContentLength = MEGABYTE_LENGTH * config.getInt("maxContentLengthMb", 10);

        // build rate limit caches
        this.postRateLimiter = new RateLimiter(
                config.getInt("postRateLimitPeriodMins", 10),
                config.getInt("postRateLimit", 30)
        );
        this.readRateLimiter = new RateLimiter(
                config.getInt("readRateLimitPeriodMins", 10),
                config.getInt("readRateLimit", 100)
        );

        // make directories
        Files.createDirectories(this.contentPath);

        // load index page
        try (InputStreamReader in = new InputStreamReader(Bytebin.class.getResourceAsStream("/index.html"), StandardCharsets.UTF_8)) {
            this.indexPage = CharStreams.toString(in).getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // setup the web server
        this.server = createWebServer();

        // schedule invalidation task
        this.executor.scheduleAtFixedRate(new InvalidationRunnable(), 1, cacheTimeMins, TimeUnit.MINUTES);
    }

    /** Standard response function. (always add the CORS header)*/
    private static Resp cors(Resp resp) {
        return resp.header("Access-Control-Allow-Origin", "*");
    }

    private Setup createWebServer() {
        Setup server = Setup.create("bytebin");

        // define bind host & port
        server.address(this.host).port(this.port);

        // catch all errors & just return some generic error message
        server.custom().errorHandler((req, resp, error) -> cors(resp).code(404).plain("Invalid path"));

        // define option route handlers
        defineOptionsRoute(server, "/post", "POST");
        defineOptionsRoute(server, "/*", "GET");

        // serve index page
        server.page("/").html(this.indexPage);

        // define upload path
        server.post("/post").managed(false).serve(req -> {
            AtomicReference<byte[]> content = new AtomicReference<>(req.body());

            String ipAddress = getIpAddress(req);

            // ensure something was actually posted
            if (content.get().length == 0) return cors(req.response()).code(400).plain("Missing content");
            // check rate limits
            if (this.postRateLimiter.check(ipAddress)) return cors(req.response()).code(429).plain("Rate limit exceeded");

            // determine the mediatype
            MediaType mediaType = determineMediaType(req);

            // generate a key
            String key = this.tokenGenerator.generate();

            // is the content already compressed?
            boolean compressed = req.header("Content-Encoding", "").equals("gzip");

            // if compression is required at a later stage
            AtomicBoolean requiresCompression = new AtomicBoolean(false);

            // if it's not compressed, consider the effect of compression on the content length
            if (!compressed) {
                // if the max content length would be exceeded - try compressing
                if (content.get().length > this.maxContentLength) {
                    content.set(compress(content.get()));
                } else {
                    // compress later
                    requiresCompression.set(true);
                }
            }

            long expiry = System.currentTimeMillis() + this.lifetimeMillis;

            // check max content length
            if (content.get().length > this.maxContentLength) return cors(req.response()).code(413).plain("Content too large");

            this.loggingExecutor.submit(() -> {
                String hostname = null;
                try {
                    InetAddress inetAddress = InetAddress.getByName(ipAddress);
                    hostname = inetAddress.getCanonicalHostName();
                    if (ipAddress.equals(hostname)) {
                        hostname = null;
                    }
                } catch (Exception e) {
                    // ignore
                }

                this.logger.info("[POST]");
                this.logger.info("    key = " + key);
                this.logger.info("    type = " + new String(mediaType.getBytes()));
                this.logger.info("    user agent = " + req.header("User-Agent", "null"));
                this.logger.info("    origin = " + ipAddress + (hostname != null ? " (" + hostname + ")" : ""));
                this.logger.info("    content size = " + String.format("%,d", content.get().length / 1024) + " KB");
                this.logger.info("    compressed = " + !requiresCompression.get());
                this.logger.info("");
            });

            // record the content in the cache
            CompletableFuture<Content> future = new CompletableFuture<>();
            this.contentCache.put(key, future);

            // save the data to the filesystem
            this.executor.execute(() -> this.loader.save(key, mediaType, content.get(), expiry, requiresCompression.get(), future));

            // return the url location as plain content
            return cors(req.response()).code(201)
                    .header("Location", key)
                    .header("Expiry", DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(expiry).atOffset(ZoneOffset.UTC)))
                    .json(U.map("key", key));
        });

        // serve content
        server.get("/*").managed(false).cacheCapacity(0).serve(req -> {
            // get the requested path
            String path = req.path().substring(1);
            if (path.trim().isEmpty() || path.contains(".") || TokenGenerator.INVALID_TOKEN_PATTERN.matcher(path).find()) {
                return cors(req.response()).code(404).plain("Invalid path");
            }

            String ipAddress = getIpAddress(req);

            // check rate limits
            if (this.readRateLimiter.check(ipAddress)) return cors(req.response()).code(429).plain("Rate limit exceeded");

            // request the file from the cache async
            boolean supportsCompression = acceptsCompressed(req);

            this.loggingExecutor.submit(() -> {
                String hostname = null;
                try {
                    InetAddress inetAddress = InetAddress.getByName(ipAddress);
                    hostname = inetAddress.getCanonicalHostName();
                    if (ipAddress.equals(hostname)) {
                        hostname = null;
                    }
                } catch (Exception e) {
                    // ignore
                }

                this.logger.info("[REQUEST]");
                this.logger.info("    key = " + path);
                this.logger.info("    user agent = " + req.header("User-Agent", "null"));
                this.logger.info("    origin = " + ipAddress + (hostname != null ? " (" + hostname + ")" : ""));
                this.logger.info("    supports compression = " + supportsCompression);
                this.logger.info("");
            });

            this.contentCache.get(path).whenCompleteAsync((content, throwable) -> {
                if (throwable != null || content == null || content.key == null || content.content.length == 0) {
                    cors(req.response()).code(404).plain("Invalid path").done();
                    return;
                }

                String expiryTime = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(content.expiry).atOffset(ZoneOffset.UTC));

                // will the client accept the content in a compressed form?
                if (supportsCompression) {
                    cors(req.response()).code(200)
                            .header("Cache-Control", "public, max-age=86400")
                            .header("Content-Encoding", "gzip")
                            .header("Expires", expiryTime)
                            .body(content.content)
                            .contentType(content.mediaType)
                            .done();
                    return;
                }

                // need to uncompress
                byte[] uncompressed;
                try {
                    uncompressed = decompress(content.content);
                } catch (IOException e) {
                    cors(req.response()).code(404).plain("Unable to uncompress data").done();
                    return;
                }

                // return the data
                cors(req.response()).code(200)
                        .header("Cache-Control", "public, max-age=86400")
                        .header("Expires", expiryTime)
                        .body(uncompressed)
                        .contentType(content.mediaType)
                        .done();
            });

            // mark that we're going to respond later
            return req.async();
        });

        server.activate();
        return server;
    }

    @Override
    public void close() {
        this.server.halt();
        this.executor.shutdown();
        try {
            this.executor.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

    }

    private static Logger createLogger() {
        Logger logger = Logger.getLogger("bytebin");
        logger.setLevel(Level.ALL);
        logger.setUseParentHandlers(false);
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new Formatter() {
            private final DateFormat dateFormat = DateFormat.getTimeInstance(DateFormat.MEDIUM);

            @Override
            public String format(LogRecord record) {
                return String.format(
                        "%s [%s] %s\n",
                        this.dateFormat.format(new Date(record.getMillis())),
                        record.getLevel().getName(),
                        record.getMessage()
                );
            }
        });
        logger.addHandler(consoleHandler);
        return logger;
    }

    private static void defineOptionsRoute(Setup setup, String path, String allowedMethod) {
        setup.options(path).serve(req -> cors(req.response())
                .header("Access-Control-Allow-Methods", allowedMethod)
                .header("Access-Control-Max-Age", "86400")
                .header("Access-Control-Allow-Headers", "Content-Type")
                .code(200)
                .body(EMPTY_BYTES)
        );
    }

    private static MediaType determineMediaType(Req req) {
        MediaType mt = req.contentType();
        if (mt == null) {
            mt = MediaType.TEXT_PLAIN;
        }
        return mt;
    }

    private static boolean acceptsCompressed(Req req) {
        boolean acceptCompressed = false;
        String header = req.header("Accept-Encoding", null);
        if (header != null && Arrays.stream(header.split(", ")).anyMatch(s -> s.equals("gzip"))) {
            acceptCompressed = true;
        }
        return acceptCompressed;
    }

    private static byte[] compress(byte[] buf) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(buf.length);
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(out)) {
            gzipOut.write(buf);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private static byte[] decompress(byte[] buf) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(buf);
        try (GZIPInputStream gzipIn = new GZIPInputStream(in)) {
            return ByteStreams.toByteArray(gzipIn);
        }
    }

    /**
     * Manages content i/o with the filesystem, including encoding an instance of {@link Content} into
     * a single array of bytes
     */
    private final class ContentLoader implements CacheLoader<String, Content> {

        @Override
        public Content load(String path) throws IOException {
            Bytebin.this.logger.info("[I/O] Loading " + path + " from disk");

            // resolve the path within the content dir
            Path resolved = Bytebin.this.contentPath.resolve(path);
            return load(resolved);
        }

        public Content load(Path resolved) throws IOException {
            if (!Files.exists(resolved)) {
                return EMPTY_CONTENT;
            }

            try (DataInputStream in = new DataInputStream(Files.newInputStream(resolved))) {
                // read key
                String key = in.readUTF();

                // read content type
                byte[] contentType = new byte[in.readInt()];
                in.readFully(contentType);
                MediaType mediaType = MediaType.of(new String(contentType));

                // read expiry
                long expiry = in.readLong();

                // read content
                byte[] content = new byte[in.readInt()];
                in.readFully(content);

                return new Content(key, mediaType, expiry, content);
            }
        }

        public Content loadMeta(Path resolved) throws IOException {
            if (!Files.exists(resolved)) {
                return EMPTY_CONTENT;
            }

            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(resolved)))) {
                // read key
                String key = in.readUTF();

                // read content type
                byte[] contentType = new byte[in.readInt()];
                in.readFully(contentType);
                MediaType mediaType = MediaType.of(new String(contentType));

                // read expiry
                long expiry = in.readLong();

                return new Content(key, mediaType, expiry, EMPTY_BYTES);
            }
        }

        public void save(String key, MediaType mediaType, byte[] content, long expiry, boolean requiresCompression, CompletableFuture<Content> future) {
            if (requiresCompression) {
                content = compress(content);
            }

            // add directly to the cache
            // it's quite likely that the file will be requested only a few seconds after it is uploaded
            Content c = new Content(key, mediaType, expiry, content);
            future.complete(c);

            // resolve the path to save at
            Path path = Bytebin.this.contentPath.resolve(key);

            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)))) {
                // write name
                out.writeUTF(key);

                // write content type
                byte[] contextType = mediaType.getBytes();
                out.writeInt(contextType.length);
                out.write(contextType);

                // write expiry time
                out.writeLong(expiry);

                // write content
                out.writeInt(content.length);
                out.write(content);
            } catch (IOException e) {
                if (e instanceof FileAlreadyExistsException) {
                    Bytebin.this.logger.info("File '" + key + "' already exists.");
                    return;
                }
                e.printStackTrace();
            }
        }
    }

    private static String getIpAddress(Req req) {
        String ipAddress = req.header("x-real-ip", null);
        if (ipAddress == null) {
            ipAddress = req.clientIpAddress();
        }
        return ipAddress;
    }

    /**
     * Handles a rate limit
     */
    private static final class RateLimiter {
        /** Rate limiter cache - allow x "actions" every x minutes */
        private final LoadingCache<String, AtomicInteger> rateLimiter;
        /** The number of actions allowed in each period  */
        private final int actionsPerCycle;

        public RateLimiter(int periodMins, int actionsPerCycle) {
            this.rateLimiter = Caffeine.newBuilder()
                    .expireAfterWrite(periodMins, TimeUnit.MINUTES)
                    .build(key -> new AtomicInteger(0));
            this.actionsPerCycle = actionsPerCycle;
        }

        public boolean check(String ipAddress) {
            //noinspection ConstantConditions
            return this.rateLimiter.get(ipAddress).incrementAndGet() > this.actionsPerCycle;
        }
    }

    /**
     * Encapsulates content within the service
     */
    private static final class Content {
        private final String key;
        private final MediaType mediaType;
        private final long expiry;
        private final byte[] content;

        private Content(String key, MediaType mediaType, long expiry, byte[] content) {
            this.key = key;
            this.mediaType = mediaType;
            this.expiry = expiry;
            this.content = content;
        }

        private boolean shouldExpire() {
            return this.expiry < System.currentTimeMillis();
        }
    }

    /**
     * Task to delete expired content
     */
    private final class InvalidationRunnable implements Runnable {
        @Override
        public void run() {
            try (Stream<Path> stream = Files.list(Bytebin.this.contentPath)) {
                stream.filter(Files::isRegularFile)
                        .forEach(path -> {
                            try {
                                Content content = Bytebin.this.loader.loadMeta(path);
                                if (content.shouldExpire()) {
                                    Bytebin.this.logger.info("Expired: " + path.getFileName().toString());
                                    Files.delete(path);
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        });
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Json config wrapper class
     */
    private static final class Configuration {
        private final JsonObject jsonObject;

        public Configuration(JsonObject jsonObject) {
            this.jsonObject = jsonObject;
        }

        public String getString(String path, String def) {
            JsonElement e = this.jsonObject.get(path);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
                return def;
            }
            return e.getAsString();
        }

        public int getInt(String path, int def) {
            JsonElement e = this.jsonObject.get(path);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                return def;
            }
            return e.getAsInt();
        }

        public long getLong(String path, long def) {
            JsonElement e = this.jsonObject.get(path);
            if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
                return def;
            }
            return e.getAsLong();
        }
    }

    /**
     * Randomly generates tokens for new content uploads
     */
    private static final class TokenGenerator {
        /** Pattern to match invalid tokens */
        public static final Pattern INVALID_TOKEN_PATTERN = Pattern.compile("[^a-zA-Z0-9]");

        /** Characters to include in a token */
        private static final String CHARACTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

        private final int length;
        private final SecureRandom random = new SecureRandom();

        public TokenGenerator(int length) {
            Preconditions.checkArgument(length > 1);
            this.length = length;
        }

        public String generate() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < this.length; i++) {
                sb.append(CHARACTERS.charAt(this.random.nextInt(CHARACTERS.length())));
            }
            return sb.toString();
        }
    }
}