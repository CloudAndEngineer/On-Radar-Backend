package kr.todak.core.config

import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration
import java.sql.Clob

/**
 * 팀 schema.sql 의 TEXT 컬럼(transcript, summary, memo, reasons_json, ai_summary)은 H2 에서 CLOB 입니다.
 * 읽을 때 String 으로 바꿔 주는 변환기를 등록합니다.
 */
@Configuration
class JdbcConfig : AbstractJdbcConfiguration() {
    override fun userConverters(): List<*> = listOf(ClobToStringConverter())
}

@ReadingConverter
class ClobToStringConverter : Converter<Clob, String> {
    override fun convert(source: Clob): String = source.getSubString(1, source.length().toInt())
}
