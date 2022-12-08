package org.geotools.data.bigquery;

import com.google.cloud.bigquery.Clustering;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.FieldList;
import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.JobException;
import com.google.cloud.bigquery.QueryJobConfiguration;
import com.google.cloud.bigquery.RangePartitioning;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.StandardSQLTypeName;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;
import com.google.cloud.bigquery.TimePartitioning;
import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.geotools.data.FeatureReader;
import org.geotools.data.Query;
import org.geotools.data.store.ContentEntry;
import org.geotools.data.store.ContentFeatureSource;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.util.logging.Logging;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

/**
 * Read-only access to a Bigquery table
 *
 * @author traviswebb
 */
public class BigqueryFeatureSource extends ContentFeatureSource {

    private static final Logger LOGGER = Logging.getLogger(BigqueryFeatureSource.class);

    protected static final Map<StandardSQLTypeName, Class<?>> BQ_TYPE_MAP =
            new ImmutableMap.Builder<StandardSQLTypeName, Class<?>>()
                    .put(StandardSQLTypeName.GEOGRAPHY, Geometry.class)
                    .put(StandardSQLTypeName.BIGNUMERIC, BigDecimal.class)
                    .put(StandardSQLTypeName.BOOL, Boolean.class)
                    .put(StandardSQLTypeName.INT64, BigInteger.class)
                    .put(StandardSQLTypeName.FLOAT64, Float.class)
                    .put(StandardSQLTypeName.NUMERIC, BigDecimal.class)
                    .put(StandardSQLTypeName.STRING, String.class)
                    .put(StandardSQLTypeName.DATE, Date.class)
                    .put(StandardSQLTypeName.DATETIME, Date.class)
                    .put(StandardSQLTypeName.TIME, Date.class)
                    .put(StandardSQLTypeName.TIMESTAMP, Date.class)
                    .put(StandardSQLTypeName.BYTES, String.class)
                    .put(StandardSQLTypeName.ARRAY, List.class)
                    .put(StandardSQLTypeName.INTERVAL, String.class)
                    .put(StandardSQLTypeName.STRUCT, String.class)
                    .put(StandardSQLTypeName.JSON, String.class)
                    .build();

    private final String geomColumn;
    private final String tableName;
    private final BigqueryDataStore store;

    public BigqueryFeatureSource(ContentEntry entry) throws IOException {
        super(entry, null);

        tableName = getTableName();
        geomColumn = getAbsoluteSchema().getGeometryDescriptor().getLocalName();
        store = getDataStore();
    }

    protected String getTableName() {
        String[] parts = entry.getTypeName().split("\\.");
        return parts[parts.length - 1];
    }

    @Override
    public BigqueryDataStore getDataStore() {
        return (BigqueryDataStore) super.getDataStore();
    }

    @Override
    protected ReferencedEnvelope getBoundsInternal(Query query) throws IOException {
        Table tableRef = store.queryClient.getTable(TableId.of(store.datasetName, tableName));

        String sql =
                String.format(
                        "SELECT ST_EXTENT(%s) as extent FROM `%s`",
                        geomColumn, entry.getTypeName());
        QueryJobConfiguration queryConfig = QueryJobConfiguration.newBuilder(sql).build();

        try {
            TableResult results = tableRef.getBigQuery().query(queryConfig);
            FieldValueList row = results.getValues().iterator().next();
            FieldValueList extent = row.get("extent").getRecordValue();

            return new ReferencedEnvelope(
                    extent.get("xmin").getDoubleValue(),
                    extent.get("xmax").getDoubleValue(),
                    extent.get("ymin").getDoubleValue(),
                    extent.get("ymax").getDoubleValue(),
                    getDataStore().CRS);
        } catch (JobException e) {
            e.printStackTrace();
            return new ReferencedEnvelope();
        } catch (InterruptedException e) {
            e.printStackTrace();
            return new ReferencedEnvelope();
        }
    }

    @Override
    protected int getCountInternal(Query query) throws IOException {
        BigqueryDataStore store = getDataStore();
        Table tableRef = store.queryClient.getTable(TableId.of(store.datasetName, tableName));
        return tableRef.getNumRows().intValue();
    }

    @Override
    protected FeatureReader<SimpleFeatureType, SimpleFeature> getReaderInternal(Query query)
            throws IOException {
        if (store.accessMethod == BigqueryAccessMethod.STORAGE_API) {
            return new BigqueryStorageReader(getState(), query);
        } else {
            return new BigqueryStandardReader(getState(), query);
        }
    }

    @Override
    protected SimpleFeatureType buildFeatureType() throws IOException {
        BigqueryDataStore store = getDataStore();
        Table tableRef = store.queryClient.getTable(TableId.of(store.datasetName, tableName));
        StandardTableDefinition tableDef = tableRef.getDefinition();
        Schema schema = tableDef.getSchema();
        FieldList fields = schema.getFields();
        TimePartitioning timePartition = tableDef.getTimePartitioning();
        RangePartitioning rangePartition = tableDef.getRangePartitioning();

        Clustering tableClustering = tableDef.getClustering();
        List<String> clusterFields =
                tableClustering != null ? tableClustering.getFields() : new ArrayList<String>();
        String timePartitionField =
                timePartition != null ? timePartition.getField() : "_PARTITIONTIME";
        Boolean partitionRequired =
                timePartition != null && timePartition.getRequirePartitionFilter();
        String rangePartitionField = rangePartition != null ? rangePartition.getField() : null;

        SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.setName(entry.getTypeName());
        builder.setCRS(getDataStore().CRS);

        for (Field field : fields) {
            String fieldName = field.getName();
            Class<?> fieldType = BQ_TYPE_MAP.get(field.getType().getStandardType());
            Field.Mode fieldMode = field.getMode();
            Boolean isClustered = clusterFields.contains(fieldName);
            Boolean isPartitioned =
                    fieldName.equals(timePartitionField) || fieldName.equals(rangePartitionField);

            builder.nillable(fieldMode == Field.Mode.NULLABLE)
                    .userData("clustering", isClustered)
                    .userData("partitioning", isPartitioned)
                    .userData("partitioningRequired", partitionRequired && isPartitioned);

            if (null != timePartition) {
                builder.userData("partitioningType", timePartition.getType().toString());
            }

            builder.add(fieldName, fieldType);

            if (fieldType == Geometry.class) {
                builder.setDefaultGeometry(fieldName);
            }
        }

        if (timePartition != null && "_PARTITIONTIME".equals(timePartitionField)) {
            builder.userData("partitioning", true)
                    .userData("partitioningRequired", partitionRequired)
                    .add(timePartitionField, Date.class);
        }

        return builder.buildFeatureType();
    }
}
