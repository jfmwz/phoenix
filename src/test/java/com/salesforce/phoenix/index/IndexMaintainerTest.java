package com.salesforce.phoenix.index;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.junit.Before;
import org.junit.Test;

import com.google.common.collect.Maps;
import com.salesforce.hbase.index.builder.covered.ColumnReference;
import com.salesforce.phoenix.jdbc.PhoenixConnection;
import com.salesforce.phoenix.query.BaseConnectionlessQueryTest;
import com.salesforce.phoenix.schema.PTable;
import com.salesforce.phoenix.util.PhoenixRuntime;
import com.salesforce.phoenix.util.SchemaUtil;

public class IndexMaintainerTest  extends BaseConnectionlessQueryTest {

    @Before
    public void beforeTest() throws Exception {
        stopServer();
        startServer(getUrl());
    }
    
    
    private void testIndexRowKeyBuilding(String schemaName, String tableName, String dataColumns, String pk, String indexColumns, Object[] values) throws Exception {
        testIndexRowKeyBuilding(schemaName, tableName, dataColumns, pk, indexColumns, values, "", "", "");
    }

    private void testIndexRowKeyBuilding(String schemaName, String tableName, String dataColumns, String pk, String indexColumns, Object[] values, String includeColumns, String dataProps, String indexProps) throws Exception {

        Connection conn = DriverManager.getConnection(getUrl());
        String fullTableName = SchemaUtil.getTableDisplayName(schemaName, tableName) ;
        conn.createStatement().execute("CREATE TABLE " + fullTableName + "(" + dataColumns + " CONSTRAINT pk PRIMARY KEY (" + pk + "))  IMMUTABLE_ROWS=true " + (dataProps.isEmpty() ? "" : "," + dataProps) );
        conn.createStatement().execute("CREATE INDEX idx ON " + fullTableName + "(" + indexColumns + ") " + (includeColumns.isEmpty() ? "" : "INCLUDE (" + includeColumns + ") ") + (indexProps.isEmpty() ? "" : indexProps));
        PTable table = conn.unwrap(PhoenixConnection.class).getPMetaData().getSchema(SchemaUtil.normalizeIdentifier(schemaName)).getTable(SchemaUtil.normalizeIdentifier(tableName));
        ImmutableBytesWritable ptr = new ImmutableBytesWritable();
        table.getIndexMaintainers(Bytes.toBytes(schemaName), ptr);
        List<IndexMaintainer> c1 = IndexMaintainer.deserialize(ptr);
        assertEquals(1,c1.size());
        IndexMaintainer im1 = c1.get(0);
        
        StringBuilder buf = new StringBuilder("UPSERT INTO " + fullTableName  + " VALUES(");
        for (int i = 0; i < values.length; i++) {
            buf.append("?,");
        }
        buf.setCharAt(buf.length()-1, ')');
        PreparedStatement stmt = conn.prepareStatement(buf.toString());
        for (int i = 0; i < values.length; i++) {
            stmt.setObject(i+1, values[i]);
        }
        stmt.execute();
        	Iterator<Pair<byte[],List<KeyValue>>> iterator = PhoenixRuntime.getUncommittedDataIterator(conn);
        List<KeyValue> dataKeyValues = iterator.next().getSecond();
        Map<ColumnReference,byte[]> valueMap = Maps.newHashMapWithExpectedSize(dataKeyValues.size());
        for (KeyValue kv : dataKeyValues) {
            valueMap.put(new ColumnReference(kv.getFamily(),kv.getQualifier()), kv.getValue());
        }
        ImmutableBytesWritable rowKeyPtr = new ImmutableBytesWritable(dataKeyValues.get(0).getRow());
        List<KeyValue> indexKeyValues = iterator.next().getSecond();
        ImmutableBytesWritable indexKeyPtr = new ImmutableBytesWritable(indexKeyValues.get(0).getRow());
        
        ptr.set(rowKeyPtr.get(), rowKeyPtr.getOffset(), rowKeyPtr.getLength());
        byte[] mutablelndexRowKey = im1.buildRowKey(valueMap, ptr);
        byte[] immutableIndexRowKey = indexKeyPtr.copyBytes();
        assertArrayEquals(immutableIndexRowKey, mutablelndexRowKey);
    }

    @Test
    public void testRowKeyVarOnlyIndex() throws Exception {
        testIndexRowKeyBuilding("", "rkTest", "k1 VARCHAR, k2 DECIMAL", "k1,k2", "k2, k1", new Object [] {"a",1.1});
    }
 
    @Test
    public void testVarFixedndex() throws Exception {
        testIndexRowKeyBuilding("", "rkTest", "k1 VARCHAR, k2 INTEGER NOT NULL, v VARCHAR", "k1,k2", "k2, k1", new Object [] {"a",1.1});
    }
 
    
    @Test
    public void testCompositeRowKeyVarFixedIndex() throws Exception {
        // TODO: using 1.1 for INTEGER didn't give error
        testIndexRowKeyBuilding("", "rkTest", "k1 VARCHAR, k2 INTEGER NOT NULL, v VARCHAR", "k1,k2", "k2, k1", new Object [] {"a",1});
    }
 
    @Test
    public void testSingleKeyValueIndex() throws Exception {
        testIndexRowKeyBuilding("", "rkTest", "k1 VARCHAR, k2 INTEGER NOT NULL, v VARCHAR", "k1", "v", new Object [] {"a",1,"b"});
    }
 
    @Test
    public void testMultiKeyValueIndex() throws Exception {
        testIndexRowKeyBuilding("", "rkTest", "k1 CHAR(1) NOT NULL, k2 INTEGER NOT NULL, v1 DECIMAL, v2 CHAR(2), v3 BIGINT", "k1, k2", "v2, k2, v1", new Object [] {"a",1,2.2,"bb"});
    }
 
    @Test
    public void testSingleKeyValueDescIndex() throws Exception {
        testIndexRowKeyBuilding("", "rkTest", "k1 VARCHAR, k2 INTEGER NOT NULL, v VARCHAR", "k1", "v DESC", new Object [] {"a",1,"b"});
    }
 
    @Test
    public void testCompositeRowKeyVarFixedDescIndex() throws Exception {
        testIndexRowKeyBuilding("", "rkTest", "k1 VARCHAR, k2 INTEGER NOT NULL, v VARCHAR", "k1,k2", "k2 DESC, k1", new Object [] {"a",1});
    }
 
    @Test
    public void testCompositeDescRowKeyVarFixedDescIndex() throws Exception {
        testIndexRowKeyBuilding("", "rkTest", "k1 VARCHAR, k2 INTEGER NOT NULL, v VARCHAR", "k1, k2 DESC", "k2 DESC, k1", new Object [] {"a",1});
    }
 
    @Test
    public void testCompositeDescRowKeyVarDescIndex() throws Exception {
        testIndexRowKeyBuilding("", "rkTest", "k1 VARCHAR, k2 DECIMAL NOT NULL, v VARCHAR", "k1, k2 DESC", "k2 DESC, k1", new Object [] {"a",1.1,"b"});
    }
 
    @Test
    public void testCompositeDescRowKeyVarAscIndex() throws Exception {
        testIndexRowKeyBuilding("", "rkTest", "k1 VARCHAR, k2 DECIMAL NOT NULL, v VARCHAR", "k1, k2 DESC", "k2, k1", new Object [] {"a",1.1,"b"});
    }
 
 
}
