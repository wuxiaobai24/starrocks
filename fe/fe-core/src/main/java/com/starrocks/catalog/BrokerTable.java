// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/catalog/BrokerTable.java

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package com.starrocks.catalog;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.analysis.DescriptorTable.ReferencedPartitionInfo;
import com.starrocks.common.DdlException;
import com.starrocks.common.io.Text;
import com.starrocks.thrift.TBrokerTable;
import com.starrocks.thrift.TTableDescriptor;
import com.starrocks.thrift.TTableType;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.List;
import java.util.Map;

public class BrokerTable extends Table {
    private static final Logger LOG = LogManager.getLogger(BrokerTable.class);

    private static final String BROKER_NAME = "broker_name";
    private static final String PATH = "path";
    private static final String COLUMN_SEPARATOR = "column_separator";
    private static final String LINE_DELIMITER = "line_delimiter";
    private static final String FILE_FORMAT = "format";
    private String brokerName;
    private List<String> paths;
    private String columnSeparator;
    private String rowDelimiter;
    private String fileFormat;
    private Map<String, String> brokerProperties;

    public BrokerTable() {
        super(TableType.BROKER);
    }

    public BrokerTable(long id, String name, List<Column> schema, Map<String, String> properties)
            throws DdlException {
        super(id, name, TableType.BROKER, schema);
        validate(properties);
    }

    public void setBrokerProperties(Map<String, String> brokerProperties) {
        this.brokerProperties = brokerProperties;
        if (this.brokerProperties == null) {
            this.brokerProperties = Maps.newHashMap();
        }
    }

    public String getBrokerName() {
        return brokerName;
    }

    public List<String> getPaths() {
        return paths;
    }

    public List<String> getEncodedPaths() {
        List<String> encodedPath = Lists.newArrayList();
        // we encode ',' and '%' to %2c and %25
        for (String path : paths) {
            encodedPath.add(path.replaceAll(",", "%2c").replaceAll("%", "%25"));
        }
        return encodedPath;
    }

    public String getColumnSeparator() {
        return columnSeparator;
    }

    public String getReadableColumnSeparator() {
        return StringEscapeUtils.escapeJava(columnSeparator);
    }

    public String getRowDelimiter() {
        return rowDelimiter;
    }

    public String getReadableRowDelimiter() {
        return StringEscapeUtils.escapeJava(rowDelimiter);
    }

    public String getFileFormat() {
        return fileFormat;
    }

    public Map<String, String> getBrokerProperties() {
        return brokerProperties;
    }

    // Check whether insert into table
    public boolean isWritable() {
        for (String path : paths) {
            if (path.endsWith("/*")) {
                return true;
            }
        }

        return false;
    }

    public String getWritablePath() {
        for (String path : paths) {
            if (path.endsWith("/*")) {
                return path.substring(0, path.lastIndexOf("*"));
            }
        }
        return null;
    }

    private void validate(Map<String, String> properties) throws DdlException {
        if (properties == null) {
            throw new DdlException("Please set properties of broker table, "
                    + "they are: broker_name, path, column_delimiter, line_delimiter and format.");
        }

        Map<String, String> copiedProps = Maps.newHashMap(properties);
        brokerName = copiedProps.get(BROKER_NAME);
        if (Strings.isNullOrEmpty(brokerName)) {
            throw new DdlException("Broker name is null. "
                    + "Please add properties('broker_name'='xxx') when create table");
        }
        copiedProps.remove(BROKER_NAME);

        // TODO(zc)
        // check if broker name exist

        String pathsStr = copiedProps.get(PATH);
        if (Strings.isNullOrEmpty(pathsStr)) {
            throw new DdlException("Path is null. "
                    + "Please add properties('path'='xxx') when create table");
        }
        copiedProps.remove(PATH);
        String[] origPaths = pathsStr.split(",");
        paths = Lists.newArrayList();
        // user will write %2c and %25 instead of ',' and '%'
        // we need to decode these escape character to what they really are.
        try {
            for (String origPath : origPaths) {
                origPath = origPath.trim();
                origPath = URLDecoder.decode(origPath, "UTF-8");
                paths.add(origPath);
            }
        } catch (UnsupportedEncodingException e) {
            throw new DdlException("Encounter path encoding exception: " + e.getMessage());
        }

        columnSeparator = copiedProps.get(COLUMN_SEPARATOR);
        if (Strings.isNullOrEmpty(columnSeparator)) {
            // use default separater
            columnSeparator = "\t";
        }
        copiedProps.remove(COLUMN_SEPARATOR);

        rowDelimiter = copiedProps.get(LINE_DELIMITER);
        if (Strings.isNullOrEmpty(rowDelimiter)) {
            // use default delimiter
            rowDelimiter = "\n";
        }
        copiedProps.remove(LINE_DELIMITER);

        fileFormat = copiedProps.get(FILE_FORMAT);
        if (fileFormat != null) {
            fileFormat = fileFormat.toLowerCase();
            switch (fileFormat) {
                case "csv":
                case "parquet":
                    break;
                default:
                    throw new DdlException(
                            "Invalid file type: " + copiedProps.toString() + ".Only support csv and parquet.");
            }
        }

        copiedProps.remove(FILE_FORMAT);

        if (!copiedProps.isEmpty()) {
            throw new DdlException("Unknown table properties: " + copiedProps.toString());
        }
    }

    @Override
    public TTableDescriptor toThrift(List<ReferencedPartitionInfo> partitions) {
        TBrokerTable tBrokerTable = new TBrokerTable();
        TTableDescriptor tTableDescriptor = new TTableDescriptor(getId(), TTableType.BROKER_TABLE,
                fullSchema.size(), 0, getName(), "");
        tTableDescriptor.setBrokerTable(tBrokerTable);
        return tTableDescriptor;
    }

    @Override
    public void write(DataOutput out) throws IOException {
        super.write(out);

        Text.writeString(out, brokerName);
        out.writeInt(paths.size());
        for (String path : paths) {
            Text.writeString(out, path);
        }
        Text.writeString(out, columnSeparator);
        Text.writeString(out, rowDelimiter);
        out.writeInt(brokerProperties.size());
        for (Map.Entry<String, String> prop : brokerProperties.entrySet()) {
            Text.writeString(out, prop.getKey());
            Text.writeString(out, prop.getValue());
        }
    }

    public void readFields(DataInput in) throws IOException {
        super.readFields(in);

        brokerName = Text.readString(in);
        int size = in.readInt();
        paths = Lists.newArrayList();
        for (int i = 0; i < size; i++) {
            paths.add(Text.readString(in));
        }
        columnSeparator = Text.readString(in);
        rowDelimiter = Text.readString(in);
        brokerProperties = Maps.newHashMap();
        size = in.readInt();
        for (int i = 0; i < size; i++) {
            String key = Text.readString(in);
            String val = Text.readString(in);
            brokerProperties.put(key, val);
        }
    }
}
