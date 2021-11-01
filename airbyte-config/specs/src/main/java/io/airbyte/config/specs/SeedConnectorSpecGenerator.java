/*
 * Copyright (c) 2021 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.config.specs;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.cloud.storage.StorageOptions;
import com.google.common.annotations.VisibleForTesting;
import io.airbyte.commons.cli.Clis;
import io.airbyte.commons.io.IOs;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.util.MoreIterators;
import io.airbyte.commons.yaml.Yamls;
import io.airbyte.config.DockerImageSpec;
import io.airbyte.config.EnvConfigs;
import io.airbyte.protocol.models.ConnectorSpecification;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This script is responsible for ensuring that up-to-date {@link ConnectorSpecification}s for every
 * connector definition in the seed are stored in a corresponding resource file, for the purpose of
 * seeding the specs into the config database on server startup.
 * <p>
 * Specs are stored in a separate file from the definitions in an effort to keep the definitions
 * yaml files human-readable and easily-editable, as specs can be rather large.
 */
public class SeedConnectorSpecGenerator {

  private static final String DOCKER_REPOSITORY_FIELD = "dockerRepository";
  private static final String DOCKER_IMAGE_TAG_FIELD = "dockerImageTag";
  private static final String DOCKER_IMAGE_FIELD = "dockerImage";
  private static final String SPEC_FIELD = "spec";
  private static final String SPEC_BUCKET_NAME = new EnvConfigs().getSpecCacheBucket();

  private static final Logger LOGGER = LoggerFactory.getLogger(SeedConnectorSpecGenerator.class);

  private static final Option SEED_ROOT_OPTION = Option.builder("s").longOpt("seed-root").hasArg(true).required(true)
      .desc("path to where seed resource files are stored").build();
  private static final Options OPTIONS = new Options().addOption(SEED_ROOT_OPTION);

  private final GcsBucketSpecFetcher bucketSpecFetcher;

  SeedConnectorSpecGenerator(final GcsBucketSpecFetcher bucketSpecFetcher) {
    this.bucketSpecFetcher = bucketSpecFetcher;
  }

  public static void main(final String[] args) throws Exception {
    final CommandLine parsed = Clis.parse(args, OPTIONS);
    final Path outputRoot = Path.of(parsed.getOptionValue(SEED_ROOT_OPTION.getOpt()));

    final GcsBucketSpecFetcher bucketSpecFetcher = new GcsBucketSpecFetcher(StorageOptions.getDefaultInstance().getService(), SPEC_BUCKET_NAME);
    final SeedConnectorSpecGenerator seedConnectorSpecGenerator = new SeedConnectorSpecGenerator(bucketSpecFetcher);
    seedConnectorSpecGenerator.run(outputRoot, ConnectorType.SOURCE);
    seedConnectorSpecGenerator.run(outputRoot, ConnectorType.DESTINATION);
  }

  public void run(final Path seedRoot, final ConnectorType connectorType) throws IOException {
    LOGGER.info("Updating seeded {} definition specs if necessary...", connectorType.name());

    final JsonNode seedDefinitionsJson = yamlToJson(seedRoot, connectorType.getDefinitionFileName());
    final JsonNode seedSpecsJson = yamlToJson(seedRoot, connectorType.getSpecFileName());

    final List<DockerImageSpec> updatedSeedSpecs = fetchUpdatedSeedSpecs(seedDefinitionsJson, seedSpecsJson);

    final String outputString = String.format("# This file is generated by %s.\n", this.getClass().getName())
        + "# Do NOT edit this file directly. See generator class for more details.\n"
        + Yamls.serialize(updatedSeedSpecs);
    final Path outputPath = IOs.writeFile(seedRoot.resolve(connectorType.getSpecFileName()), outputString);

    LOGGER.info("Finished updating {}", outputPath);
  }

  private JsonNode yamlToJson(final Path root, final String fileName) {
    final String yamlString = IOs.readFile(root, fileName);
    return Yamls.deserialize(yamlString);
  }

  @VisibleForTesting
  final List<DockerImageSpec> fetchUpdatedSeedSpecs(final JsonNode seedDefinitions, final JsonNode currentSeedSpecs) {
    final List<String> seedDefinitionsDockerImages = MoreIterators.toList(seedDefinitions.elements()).stream()
        .map(json -> String.format("%s:%s", json.get(DOCKER_REPOSITORY_FIELD).asText(), json.get(DOCKER_IMAGE_TAG_FIELD).asText()))
        .collect(Collectors.toList());

    final Map<String, DockerImageSpec> currentSeedImageToSpec = MoreIterators.toList(currentSeedSpecs.elements()).stream().collect(Collectors.toMap(
        json -> json.get(DOCKER_IMAGE_FIELD).asText(),
        json -> new DockerImageSpec().withDockerImage(json.get(DOCKER_IMAGE_FIELD).asText())
            .withSpec(Jsons.object(json.get(SPEC_FIELD), ConnectorSpecification.class))));

    return seedDefinitionsDockerImages.stream()
        .map(dockerImage -> currentSeedImageToSpec.containsKey(dockerImage) ? currentSeedImageToSpec.get(dockerImage) : fetchSpecFromGCS(dockerImage))
        .collect(Collectors.toList());
  }

  private DockerImageSpec fetchSpecFromGCS(final String dockerImage) {
    LOGGER.info("Seeded spec not found for docker image {} - fetching from GCS bucket {}...", dockerImage, bucketSpecFetcher.getBucketName());
    final ConnectorSpecification spec = bucketSpecFetcher.attemptFetch(dockerImage)
        .orElseThrow(() -> new RuntimeException(String.format(
            "Failed to fetch valid spec file for docker image %s from GCS bucket %s",
            dockerImage,
            bucketSpecFetcher.getBucketName())));
    return new DockerImageSpec().withDockerImage(dockerImage).withSpec(spec);
  }

}
