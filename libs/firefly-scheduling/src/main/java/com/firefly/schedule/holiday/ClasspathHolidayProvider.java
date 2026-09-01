package com.firefly.schedule.holiday;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Provider for audited official datasets packaged with the application.
 *
 * <p>Each year is stored at {@code {root}/{jurisdiction}/{year}.json} with a
 * mandatory sibling {@code .sha256} file containing the SHA-256 of the JSON
 * bytes. The provider never performs network I/O.</p>
 */
public final class ClasspathHolidayProvider implements HolidayProvider {
    private final String providerId;
    private final String resourceRoot;
    private final Set<String> jurisdictions;
    private final ClassLoader loader;
    private final ObjectMapper mapper;

    public ClasspathHolidayProvider(String providerId, String resourceRoot, Set<String> jurisdictions) {
        this(providerId, resourceRoot, jurisdictions, Thread.currentThread().getContextClassLoader());
    }

    public ClasspathHolidayProvider(
            String providerId,
            String resourceRoot,
            Set<String> jurisdictions,
            ClassLoader loader
    ) {
        if (providerId == null || providerId.isBlank()) throw new IllegalArgumentException("providerId must not be blank");
        if (resourceRoot == null || resourceRoot.isBlank()) throw new IllegalArgumentException("resourceRoot must not be blank");
        this.providerId = providerId;
        this.resourceRoot = trimSlashes(resourceRoot);
        this.jurisdictions = normalizeJurisdictions(jurisdictions);
        this.loader = Objects.requireNonNull(loader, "loader");
        this.mapper = new ObjectMapper();
    }

    @Override
    public String id() {
        return providerId;
    }

    @Override
    public Set<String> supportedJurisdictions() {
        return jurisdictions;
    }

    @Override
    public HolidayDataset fetch(HolidayQuery query) throws HolidayProviderException {
        Objects.requireNonNull(query, "query");
        String jurisdiction = query.jurisdiction().toUpperCase(Locale.ROOT);
        if (!jurisdictions.contains(jurisdiction)) {
            throw new HolidayProviderException("unsupported jurisdiction: " + query.jurisdiction());
        }
        List<HolidayOccurrence> occurrences = new ArrayList<>();
        String providerVersion = null;
        URI sourceUri = null;
        String combinedChecksum = null;
        for (int year = query.from().getYear(); year <= query.to().getYear(); year++) {
            YearData yearData = readYear(jurisdiction, year);
            if (providerVersion == null) providerVersion = yearData.providerVersion();
            if (!providerVersion.equals(yearData.providerVersion())) {
                throw new HolidayProviderException("provider version changes inside one query: " + jurisdiction);
            }
            if (!jurisdiction.equals(yearData.jurisdiction())) {
                throw new HolidayProviderException("dataset jurisdiction does not match resource path: " + jurisdiction);
            }
            occurrences.addAll(yearData.occurrences());
            sourceUri = sourceUri == null ? yearData.sourceUri() : sourceUri;
            combinedChecksum = combinedChecksum == null
                    ? yearData.checksum()
                    : sha256((combinedChecksum + ":" + yearData.checksum()).getBytes(StandardCharsets.UTF_8));
        }
        if (query.expectedProviderVersion() != null && !query.expectedProviderVersion().isBlank()
                && !query.expectedProviderVersion().equals(providerVersion)) {
            throw new HolidayProviderException("expected provider version " + query.expectedProviderVersion()
                    + " but packaged data is " + providerVersion);
        }
        HolidayDataset dataset = new HolidayDataset(providerId, providerVersion, jurisdiction,
                query.from(), query.to(), HolidayDatasetValidator.deduplicate(occurrences), sourceUri,
                combinedChecksum, true);
        try {
            HolidayDatasetValidator.validate(query, dataset);
        } catch (IllegalArgumentException invalid) {
            throw new HolidayProviderException("invalid packaged holiday dataset", invalid);
        }
        return dataset;
    }

    private YearData readYear(String jurisdiction, int year) throws HolidayProviderException {
        String path = resourceRoot + "/" + jurisdiction + "/" + year + ".json";
        String checksumPath = path + ".sha256";
        byte[] bytes;
        String expectedChecksum;
        try (InputStream input = loader.getResourceAsStream(path);
             InputStream checksum = loader.getResourceAsStream(checksumPath)) {
            if (input == null) throw new HolidayProviderException("missing packaged holiday dataset: " + path);
            if (checksum == null) throw new HolidayProviderException("missing dataset checksum: " + checksumPath);
            bytes = input.readAllBytes();
            expectedChecksum = new String(checksum.readAllBytes(), StandardCharsets.US_ASCII).trim().split("\\s+")[0];
        } catch (IOException error) {
            throw new HolidayProviderException("failed to read packaged holiday dataset: " + path, error);
        }
        String actualChecksum = sha256(bytes);
        if (!actualChecksum.equalsIgnoreCase(expectedChecksum)) {
            throw new HolidayProviderException("holiday dataset checksum mismatch: " + path);
        }
        try {
            JsonNode root = mapper.readTree(bytes);
            String version = text(root, "providerVersion");
            String datasetJurisdiction = text(root, "jurisdiction").toUpperCase(Locale.ROOT);
            URI sourceUri = URI.create(text(root, "sourceUri"));
            JsonNode dates = root.get("occurrences");
            if (dates == null || !dates.isArray()) throw new HolidayProviderException("occurrences must be an array: " + path);
            List<HolidayOccurrence> occurrences = new ArrayList<>();
            for (JsonNode item : dates) {
                occurrences.add(new HolidayOccurrence(
                        LocalDate.parse(text(item, "date")),
                        HolidayKind.valueOf(text(item, "kind").toUpperCase(Locale.ROOT)),
                        text(item, "localName"),
                        text(item, "name"),
                        item.path("observed").asBoolean(false),
                        text(item, "sourceReference")
                ));
            }
            return new YearData(version, datasetJurisdiction, occurrences, sourceUri, actualChecksum);
        } catch (HolidayProviderException error) {
            throw error;
        } catch (RuntimeException | IOException error) {
            throw new HolidayProviderException("invalid packaged holiday dataset: " + path, error);
        }
    }

    private static String text(JsonNode node, String field) throws HolidayProviderException {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) throw new HolidayProviderException("missing field: " + field);
        return value.asText();
    }

    private static String trimSlashes(String value) {
        return value.replaceAll("^/+|/+$", "");
    }

    private static Set<String> normalizeJurisdictions(Set<String> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("jurisdictions must not be empty");
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) throw new IllegalArgumentException("jurisdiction must not be blank");
            normalized.add(value.toUpperCase(Locale.ROOT));
        }
        return Set.copyOf(normalized);
    }

    private static String sha256(byte[] bytes) throws HolidayProviderException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new HolidayProviderException("SHA-256 is unavailable", impossible);
        }
    }

    private record YearData(String providerVersion, String jurisdiction, List<HolidayOccurrence> occurrences,
                            URI sourceUri, String checksum) {
    }
}
