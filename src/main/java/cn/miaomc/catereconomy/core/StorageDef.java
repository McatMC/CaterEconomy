package cn.miaomc.catereconomy.core;

import java.io.File;

/** 一个存储后端的配置：mysql 为共享数据库，sqlite 为本服本地文件。 */
public final class StorageDef {
    public enum Type {
        MYSQL,
        SQLITE
    }

    public final String id;
    public final Type type;
    public final String host;
    public final int port;
    public final String database;
    public final String username;
    public final String password;
    public final String properties;
    public final int poolSize;
    public final File file;
    public final String tablePrefix;

    private StorageDef(
            String id,
            Type type,
            String host,
            int port,
            String database,
            String username,
            String password,
            String properties,
            int poolSize,
            File file,
            String tablePrefix) {
        this.id = id;
        this.type = type;
        this.host = host;
        this.port = port;
        this.database = database;
        this.username = username;
        this.password = password;
        this.properties = properties;
        this.poolSize = poolSize;
        this.file = file;
        this.tablePrefix = tablePrefix;
    }

    public static StorageDef mysql(
            String id,
            String host,
            int port,
            String database,
            String username,
            String password,
            String properties,
            int poolSize,
            String tablePrefix) {
        return new StorageDef(id, Type.MYSQL, host, port, database, username, password, properties, poolSize, null, tablePrefix);
    }

    public static StorageDef sqlite(String id, File file, String tablePrefix) {
        return new StorageDef(id, Type.SQLITE, null, 0, null, null, null, null, 1, file, tablePrefix);
    }

    public String jdbcUrl() {
        if (type == Type.SQLITE) return "jdbc:sqlite:" + file.getAbsolutePath();
        String query = properties == null || properties.isEmpty() ? "" : "?" + properties;
        return "jdbc:mysql://" + host + ':' + port + '/' + database + query;
    }

    /** 用于判断两个存储是否指向同一份数据（避免对同一张表重复刷新缓存）。 */
    public String describe() {
        return type == Type.SQLITE ? "sqlite:" + file.getAbsolutePath() : "mysql://" + host + ':' + port + '/' + database;
    }
}
