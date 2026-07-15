package com.ylcloud.contract;

import com.ylcloud.mapper.ChunkUploadMapper;
import com.ylcloud.mapper.CrossStoreOperationMapper;
import com.ylcloud.mapper.FileInfoMapper;
import com.ylcloud.mapper.MultifileMapper;
import com.ylcloud.mapper.SpaceFileMapper;
import com.ylcloud.mapper.SpaceRagTaskMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MapperSqlContractTest {
    private static final List<Class<?>> MAPPERS = List.of(
            ChunkUploadMapper.class,
            CrossStoreOperationMapper.class,
            FileInfoMapper.class,
            MultifileMapper.class,
            SpaceFileMapper.class,
            SpaceRagTaskMapper.class
    );

    @Test
    void annotationSqlDoesNotContainHtmlEscapedOperators() {
        for(Class<?> mapper : MAPPERS) {
            for(Method method : mapper.getDeclaredMethods()) {
                for(Annotation annotation : method.getDeclaredAnnotations()) {
                    String sql = annotationSql(annotation);
                    assertFalse(sql.contains("&lt;") || sql.contains("&gt;"),
                            () -> mapper.getSimpleName() + "." + method.getName() + " contains escaped SQL: " + sql);
                }
            }
        }
    }

    @Test
    void annotationSqlCanBeParsedByMyBatisXmlLanguageDriver() {
        Configuration configuration = new Configuration();
        XMLLanguageDriver languageDriver = new XMLLanguageDriver();
        for(Class<?> mapper : MAPPERS) {
            for(Method method : mapper.getDeclaredMethods()) {
                for(Annotation annotation : method.getDeclaredAnnotations()) {
                    String sql = annotationSql(annotation);
                    if(sql.isBlank()) continue;
                    SqlSource source = languageDriver.createSqlSource(configuration,sql,Map.class);
                    String parsedSql = source.getBoundSql(Map.of()).getSql();
                    assertFalse(parsedSql.contains("&lt;") || parsedSql.contains("&gt;") || parsedSql.contains("CDATA"),
                            () -> mapper.getSimpleName() + "." + method.getName() + " leaks XML syntax: " + parsedSql);
                }
            }
        }
    }

    private String annotationSql(Annotation annotation) {
        if(annotation instanceof Select select) return String.join(" ",select.value());
        if(annotation instanceof Update update) return String.join(" ",update.value());
        if(annotation instanceof Delete delete) return String.join(" ",delete.value());
        return "";
    }
}
