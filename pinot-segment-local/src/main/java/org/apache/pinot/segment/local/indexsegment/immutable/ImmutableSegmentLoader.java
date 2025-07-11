/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.segment.local.indexsegment.immutable;

import com.google.common.base.Preconditions;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import org.apache.pinot.segment.local.segment.index.column.PhysicalColumnIndexContainer;
import org.apache.pinot.segment.local.segment.index.converter.SegmentFormatConverterFactory;
import org.apache.pinot.segment.local.segment.index.loader.IndexLoadingConfig;
import org.apache.pinot.segment.local.segment.index.loader.SegmentPreProcessor;
import org.apache.pinot.segment.local.segment.index.readers.text.MultiColumnLuceneTextIndexReader;
import org.apache.pinot.segment.local.segment.virtualcolumn.VirtualColumnContext;
import org.apache.pinot.segment.local.segment.virtualcolumn.VirtualColumnProvider;
import org.apache.pinot.segment.local.segment.virtualcolumn.VirtualColumnProviderFactory;
import org.apache.pinot.segment.local.startree.v2.store.StarTreeIndexContainer;
import org.apache.pinot.segment.local.utils.SegmentOperationsThrottler;
import org.apache.pinot.segment.spi.ColumnMetadata;
import org.apache.pinot.segment.spi.ImmutableSegment;
import org.apache.pinot.segment.spi.converter.SegmentFormatConverter;
import org.apache.pinot.segment.spi.creator.SegmentVersion;
import org.apache.pinot.segment.spi.index.column.ColumnIndexContainer;
import org.apache.pinot.segment.spi.index.metadata.SegmentMetadataImpl;
import org.apache.pinot.segment.spi.loader.SegmentDirectoryLoader;
import org.apache.pinot.segment.spi.loader.SegmentDirectoryLoaderContext;
import org.apache.pinot.segment.spi.loader.SegmentDirectoryLoaderRegistry;
import org.apache.pinot.segment.spi.store.SegmentDirectory;
import org.apache.pinot.segment.spi.store.SegmentDirectoryPaths;
import org.apache.pinot.spi.data.FieldSpec;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.env.PinotConfiguration;
import org.apache.pinot.spi.utils.ReadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class ImmutableSegmentLoader {
  private ImmutableSegmentLoader() {
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(ImmutableSegmentLoader.class);

  /**
   * Loads the segment with empty schema and IndexLoadingConfig. This method is used to
   * access the segment without modifying it, i.e. in read-only mode.
   */
  public static ImmutableSegment load(File indexDir, ReadMode readMode)
      throws Exception {
    IndexLoadingConfig defaultIndexLoadingConfig = new IndexLoadingConfig();
    defaultIndexLoadingConfig.setReadMode(readMode);
    return load(indexDir, defaultIndexLoadingConfig, false, null);
  }

  /**
   * Loads the segment with specified IndexLoadingConfig.
   * This method modifies the segment like to convert segment format, add or remove indices.
   * Mostly used by UT cases to add some specific index for testing purpose.
   */
  public static ImmutableSegment load(File indexDir, IndexLoadingConfig indexLoadingConfig)
      throws Exception {
    return load(indexDir, indexLoadingConfig, true, null);
  }

  /**
   * Loads the segment with specified IndexLoadingConfig.
   * This method modifies the segment like to convert segment format, add or remove indices.
   * Mostly used by UT cases to add some specific index for testing purpose.
   */
  public static ImmutableSegment load(File indexDir, IndexLoadingConfig indexLoadingConfig,
      @Nullable SegmentOperationsThrottler segmentOperationsThrottler)
      throws Exception {
    return load(indexDir, indexLoadingConfig, true, segmentOperationsThrottler);
  }

  /**
   * Loads the segment with specified IndexLoadingConfig.
   * This method modifies the segment like to convert segment format, add or remove indices.
   */
  public static ImmutableSegment load(File indexDir, IndexLoadingConfig indexLoadingConfig, boolean needPreprocess)
      throws Exception {
    return load(indexDir, indexLoadingConfig, needPreprocess, null);
  }

  /**
   * Loads the segment with specified schema and IndexLoadingConfig, and allows to control whether to
   * modify the segment like to convert segment format, add or remove indices.
   */
  public static ImmutableSegment load(File indexDir, IndexLoadingConfig indexLoadingConfig, boolean needPreprocess,
      @Nullable SegmentOperationsThrottler segmentOperationsThrottler)
      throws Exception {
    Preconditions.checkArgument(indexDir.isDirectory(), "Index directory: %s does not exist or is not a directory",
        indexDir);

    SegmentMetadataImpl segmentMetadata = new SegmentMetadataImpl(indexDir);
    if (segmentMetadata.getTotalDocs() == 0) {
      return new EmptyIndexSegment(segmentMetadata);
    }
    if (needPreprocess) {
      preprocess(indexDir, indexLoadingConfig, segmentOperationsThrottler);
    }
    String segmentName = segmentMetadata.getName();
    SegmentDirectoryLoaderContext segmentLoaderContext =
        new SegmentDirectoryLoaderContext.Builder().setTableConfig(indexLoadingConfig.getTableConfig())
            .setSchema(indexLoadingConfig.getSchema())
            .setInstanceId(indexLoadingConfig.getInstanceId())
            .setTableDataDir(indexLoadingConfig.getTableDataDir())
            .setSegmentName(segmentName)
            .setSegmentCrc(segmentMetadata.getCrc())
            .setSegmentTier(indexLoadingConfig.getSegmentTier())
            .setInstanceTierConfigs(indexLoadingConfig.getInstanceTierConfigs())
            .setSegmentDirectoryConfigs(indexLoadingConfig.getSegmentDirectoryConfigs())
            .build();
    SegmentDirectoryLoader segmentLoader =
        SegmentDirectoryLoaderRegistry.getSegmentDirectoryLoader(indexLoadingConfig.getSegmentDirectoryLoader());
    SegmentDirectory segmentDirectory = segmentLoader.load(indexDir.toURI(), segmentLoaderContext);
    try {
      return load(segmentDirectory, indexLoadingConfig);
    } catch (Exception e) {
      LOGGER.error("Failed to load segment: {} with SegmentDirectory", segmentName, e);
      segmentDirectory.close();
      throw e;
    }
  }

  public static void preprocess(File indexDir, IndexLoadingConfig indexLoadingConfig,
      @Nullable SegmentOperationsThrottler segmentOperationsThrottler)
      throws Exception {
    Preconditions.checkArgument(indexDir.isDirectory(), "Index directory: %s does not exist or is not a directory",
        indexDir);

    SegmentMetadataImpl segmentMetadata = new SegmentMetadataImpl(indexDir);
    if (segmentMetadata.getTotalDocs() > 0) {
      if (segmentOperationsThrottler != null) {
        segmentOperationsThrottler.getSegmentAllIndexPreprocessThrottler().acquire();
      }
      try {
        convertSegmentFormat(indexDir, indexLoadingConfig, segmentMetadata);
        // Preprocess requires table config and schema
        if (indexLoadingConfig.getTableConfig() != null && indexLoadingConfig.getSchema() != null) {
          preprocessSegment(indexDir, segmentMetadata.getName(), segmentMetadata.getCrc(), indexLoadingConfig,
              segmentOperationsThrottler);
        }
      } finally {
        if (segmentOperationsThrottler != null) {
          segmentOperationsThrottler.getSegmentAllIndexPreprocessThrottler().release();
        }
      }
    }
  }

  /**
   * Load the segment represented by the SegmentDirectory object to serve queries.
   */
  public static ImmutableSegment load(SegmentDirectory segmentDirectory, IndexLoadingConfig indexLoadingConfig)
      throws Exception {
    return load(segmentDirectory, indexLoadingConfig, indexLoadingConfig.getSchema());
  }

  @Deprecated
  public static ImmutableSegment load(SegmentDirectory segmentDirectory, IndexLoadingConfig indexLoadingConfig,
      @Nullable Schema schema)
      throws Exception {
    SegmentMetadataImpl segmentMetadata = segmentDirectory.getSegmentMetadata();
    if (segmentMetadata.getTotalDocs() == 0) {
      return new EmptyIndexSegment(segmentMetadata);
    }

    // Remove columns not in schema from the metadata
    Map<String, ColumnMetadata> columnMetadataMap = segmentMetadata.getColumnMetadataMap();
    if (schema != null) {
      Set<String> columnsInMetadata = new HashSet<>(columnMetadataMap.keySet());
      columnsInMetadata.removeIf(schema::hasColumn);
      if (!columnsInMetadata.isEmpty()) {
        LOGGER.info("Skip loading columns only exist in metadata but not in schema: {}", columnsInMetadata);
        for (String column : columnsInMetadata) {
          segmentMetadata.removeColumn(column);
        }
      }
    } else {
      indexLoadingConfig.addKnownColumns(columnMetadataMap.keySet());
    }

    SegmentDirectory.Reader segmentReader = segmentDirectory.createReader();
    Map<String, ColumnIndexContainer> indexContainerMap = new Object2ObjectOpenHashMap<>(columnMetadataMap.size());
    for (Map.Entry<String, ColumnMetadata> entry : columnMetadataMap.entrySet()) {
      // FIXME: text-index only works with local SegmentDirectory
      indexContainerMap.put(entry.getKey(),
          new PhysicalColumnIndexContainer(segmentReader, entry.getValue(), indexLoadingConfig));
    }

    // Instantiate virtual columns
    String segmentName = segmentMetadata.getName();
    Schema segmentSchema = segmentMetadata.getSchema();
    VirtualColumnProviderFactory.addBuiltInVirtualColumnsToSegmentSchema(segmentSchema, segmentName);
    for (FieldSpec fieldSpec : segmentSchema.getAllFieldSpecs()) {
      if (fieldSpec.isVirtualColumn()) {
        String columnName = fieldSpec.getName();
        VirtualColumnContext context = new VirtualColumnContext(fieldSpec, segmentMetadata.getTotalDocs());
        VirtualColumnProvider provider = VirtualColumnProviderFactory.buildProvider(context);
        indexContainerMap.put(columnName, provider.buildColumnIndexContainer(context));
        columnMetadataMap.put(columnName, provider.buildMetadata(context));
      }
    }

    // Load star-tree index if it exists
    StarTreeIndexContainer starTreeIndexContainer = null;
    if (segmentReader.hasStarTreeIndex()) {
      starTreeIndexContainer = new StarTreeIndexContainer(segmentReader, segmentMetadata, indexContainerMap);
    }

    MultiColumnLuceneTextIndexReader mcTextReader = null;
    if (segmentReader.hasMultiColumnTextIndex()) {
      mcTextReader = new MultiColumnLuceneTextIndexReader(segmentMetadata);
      for (String column : segmentMetadata.getMultiColumnTextMetadata().getColumns()) {
        ColumnIndexContainer container = indexContainerMap.get(column);
        if (container instanceof PhysicalColumnIndexContainer) {
          ((PhysicalColumnIndexContainer) container).setMultiColumnTextIndex(mcTextReader);
        }
      }
    }

    ImmutableSegmentImpl segment =
        new ImmutableSegmentImpl(segmentDirectory, segmentMetadata, indexContainerMap, starTreeIndexContainer,
            mcTextReader);
    LOGGER.info("Successfully loaded segment: {} with SegmentDirectory", segmentName);
    return segment;
  }

  /**
   * Check segment directory against the IndexLoadingConfig to see if any preprocessing is needed, such as changing
   * segment format, adding new indices or updating default columns.
   */
  public static boolean needPreprocess(SegmentDirectory segmentDirectory, IndexLoadingConfig indexLoadingConfig)
      throws Exception {
    if (indexLoadingConfig.isSkipSegmentPreprocess()) {
      return false;
    }
    if (needConvertSegmentFormat(indexLoadingConfig, segmentDirectory.getSegmentMetadata())) {
      return true;
    }
    // Preprocess requires table config and schema
    if (indexLoadingConfig.getTableConfig() == null || indexLoadingConfig.getSchema() == null) {
      return false;
    }
    return new SegmentPreProcessor(segmentDirectory, indexLoadingConfig).needProcess();
  }

  private static boolean needConvertSegmentFormat(IndexLoadingConfig indexLoadingConfig,
      SegmentMetadataImpl segmentMetadata) {
    SegmentVersion segmentVersionToLoad = indexLoadingConfig.getSegmentVersion();
    return segmentVersionToLoad != null && segmentVersionToLoad != segmentMetadata.getVersion();
  }

  private static void convertSegmentFormat(File indexDir, IndexLoadingConfig indexLoadingConfig,
      SegmentMetadataImpl localSegmentMetadata)
      throws Exception {
    SegmentVersion segmentVersionToLoad = indexLoadingConfig.getSegmentVersion();
    if (segmentVersionToLoad == null || SegmentDirectoryPaths.segmentDirectoryFor(indexDir, segmentVersionToLoad)
        .isDirectory()) {
      return;
    }
    SegmentVersion segmentVersionOnDisk = localSegmentMetadata.getVersion();
    if (segmentVersionOnDisk == segmentVersionToLoad) {
      return;
    }
    String segmentName = indexDir.getName();
    LOGGER.info("Segment: {} needs to be converted from version: {} to {}", segmentName, segmentVersionOnDisk,
        segmentVersionToLoad);
    SegmentFormatConverter converter =
        SegmentFormatConverterFactory.getConverter(segmentVersionOnDisk, segmentVersionToLoad);
    LOGGER.info("Using converter: {} to up-convert segment: {}", converter.getClass().getSimpleName(), segmentName);
    converter.convert(indexDir);
    LOGGER.info("Successfully up-converted segment: {} from version: {} to {}", segmentName, segmentVersionOnDisk,
        segmentVersionToLoad);
  }

  private static void preprocessSegment(File indexDir, String segmentName, String segmentCrc,
      IndexLoadingConfig indexLoadingConfig, @Nullable SegmentOperationsThrottler segmentOperationsThrottler)
      throws Exception {
    PinotConfiguration segmentDirectoryConfigs = indexLoadingConfig.getSegmentDirectoryConfigs();
    SegmentDirectoryLoaderContext segmentLoaderContext =
        new SegmentDirectoryLoaderContext.Builder().setTableConfig(indexLoadingConfig.getTableConfig())
            .setSchema(indexLoadingConfig.getSchema())
            .setInstanceId(indexLoadingConfig.getInstanceId())
            .setSegmentName(segmentName)
            .setSegmentCrc(segmentCrc)
            .setSegmentDirectoryConfigs(segmentDirectoryConfigs)
            .build();
    SegmentDirectory segmentDirectory =
        SegmentDirectoryLoaderRegistry.getDefaultSegmentDirectoryLoader().load(indexDir.toURI(), segmentLoaderContext);
    try (SegmentPreProcessor preProcessor = new SegmentPreProcessor(segmentDirectory, indexLoadingConfig)) {
      preProcessor.process(segmentOperationsThrottler);
    }
  }
}
