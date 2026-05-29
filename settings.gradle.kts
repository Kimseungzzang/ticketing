rootProject.name = "ticket-query-service"

// 직접 만든 카프카 클론을 composite build로 포함 → publish 단계 없이 소스 변경이 바로 반영.
// build.gradle.kts의 implementation("com.example:MyKafka:...") 좌표가 이 빌드로 substitution 된다.
includeBuild("../MyKafka")
