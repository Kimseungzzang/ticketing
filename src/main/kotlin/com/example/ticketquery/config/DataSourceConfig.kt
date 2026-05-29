package com.example.ticketquery.config

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

// Primary/Replica read-write 라우팅.
//   - 쓰기(consumer ReadModelUpdater, @Transactional) → WRITER(primary, ticket_read_db).
//   - 읽기 API(@Transactional(readOnly=true)) → READER(replica/standby).
//   - replica는 primary의 Postgres 스트리밍 standby. consumer가 primary에 쓰면 standby로 복제되고,
//     읽기는 standby에서 → read 부하가 write와 물리적으로 분리(§11.3의 "진짜 이득").
//     이벤트 propagation lag 위에 **복제 lag**이 한 층 더 생긴다.
//
// REPLICA_* env 미설정 시 replica URL이 primary로 폴백 → standby 없이도 그대로 동작(회귀 없음).
@Configuration
class DataSourceConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    enum class Route { WRITER, READER }

    // 트랜잭션의 readOnly 플래그로 라우팅 결정.
    class ReadWriteRoutingDataSource : AbstractRoutingDataSource() {
        override fun determineCurrentLookupKey(): Any =
            if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) Route.READER else Route.WRITER
    }

    @Bean(destroyMethod = "close")
    fun writerDataSource(
        @Value("\${datasource.primary.url}") url: String,
        @Value("\${datasource.username}") user: String,
        @Value("\${datasource.password:}") pass: String,
    ): HikariDataSource = hikari(url, user, pass, readOnly = false, name = "writer-primary")

    @Bean(destroyMethod = "close")
    fun readerDataSource(
        @Value("\${datasource.replica.url}") url: String,
        @Value("\${datasource.username}") user: String,
        @Value("\${datasource.password:}") pass: String,
    ): HikariDataSource = hikari(url, user, pass, readOnly = true, name = "reader-replica")

    @Bean
    @Primary
    fun dataSource(
        @Qualifier("writerDataSource") writer: HikariDataSource,
        @Qualifier("readerDataSource") reader: HikariDataSource,
    ): DataSource {
        log.info("datasource routing: WRITER={} READER={}", writer.jdbcUrl, reader.jdbcUrl)
        val routing = ReadWriteRoutingDataSource()
        routing.setTargetDataSources(mapOf<Any, Any>(Route.WRITER to writer, Route.READER to reader))
        routing.setDefaultTargetDataSource(writer) // 트랜잭션 밖(예: 부팅 시 ddl)은 writer
        routing.afterPropertiesSet()
        // LazyConnectionDataSourceProxy: 실제 커넥션 획득을 첫 사용 시점까지 미뤄,
        //   @Transactional(readOnly) 플래그가 세팅된 뒤에 라우팅이 결정되게 한다(Spring 공식 패턴).
        return LazyConnectionDataSourceProxy(routing)
    }

    private fun hikari(url: String, user: String, pass: String, readOnly: Boolean, name: String): HikariDataSource {
        val cfg = HikariConfig()
        cfg.jdbcUrl = url
        cfg.username = user
        cfg.password = pass
        cfg.isReadOnly = readOnly
        cfg.poolName = name
        cfg.driverClassName = "org.postgresql.Driver"
        return HikariDataSource(cfg)
    }
}
