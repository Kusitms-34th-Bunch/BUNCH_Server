# #23 검증 결과

검증일: 2026-10-25 (Asia/Seoul)

- 기준 브랜치: 최신 확인한 `origin/dev` (`5db1240`). 작업 브랜치: `feat/23-image-photo-foundation`.
- 독립 관리형 작업 공간에서 구현했다. 기본 작업 공간의 README·ARCHITECTURE 변경과
  `docs/bundle-spec`의 스테이징 파일·index는 변경하지 않았다.
- ImageAsset·Photo·공동 업로더, 프로필 nullable FK 전환과 기존 응답 보존,
  FK 참조 보호와 커밋 후 삭제, 사진함 담당자용 연결 계약을 구현했다.
- 직접 이미지 컬럼을 제거하고 기존 파일 저장소의 JPEG 검증·삭제 처리를 재사용했다.
  새 의존성·참조 카운터·정리 배치·재시도 큐·미래 bundle 테이블은 추가하지 않았다.

## 실행 결과

JDK 21.0.10, 실제 PostgreSQL 16과 Redis 7에서 검증했다. 이 작업에서 생성한
전용 DB `bunch_issue23_test`를 사용했다. 기존 프로젝트 DB는 테스트하지 않았다.

```bash
DB_URL=jdbc:postgresql://localhost:15432/bunch_issue23_test REDIS_PORT=16379 ./gradlew clean build
```

`JAVA_HOME`은 로컬 JDK 21을 지정했다. 결과는 **BUILD SUCCESSFUL**, 26 suites,
173 tests, failures 0, errors 0, skipped 0이다.

- ImageMigrationIntegrationTest 4개: V4 기존 데이터→V5 백필, 동일 public ID 공유,
  기본 이미지, legacy 컬럼 제거, 불완전 쌍·URL 충돌의 전체 롤백, CHECK·unique·FK,
  마이그레이션 validate·재실행, 보호 인덱스.
- ImageAssetIntegrationTest 5개: 개인·번치·Photo 공유 참조 보호, rollback 후 미삭제,
  반복 해제 1회 삭제, 새로운 restrictive FK fixture의 참조 보호, 참조 생성과 정리 경합,
  삭제 실패의 DB 성공 유지 및 식별자만 기록.
- PhotoIntegrationTest 10개: 선택 순서·누락/타 번치/비정상 상태 거부,
  연결 트랜잭션 필수, 공동 업로더·최종 삭제, callback 실패 rollback,
  두 요청의 최종 삭제 1회, 촬영 정보·묶음 UUID·서버 시각 DB 왕복.
- 기존 가입·본인 프로필·번치 이미지·초대 응답, JPEG 검증·메타데이터 제거,
  인증·온보딩·Redis·공통 기반을 전체 빌드에서 검증했다.
- 최종 독립 검토: 최소 변경·YAGNI·확정 Spec 일치 PASS, 확인된 필수 수정 0건.
  선택적 지적인 미사용 `findByPhotoIdAndMemberId` 선언을 제거했다.
  전체 사진함/번들 연결 경합은 후속 통합 검증 대상이다.
- `git diff --check`: 통과. 변경 문서의 로컬 링크 점검: 통과.
- 별도 lint·정적 분석 도구는 저장소에 구성되지 않아 실행하지 않았다.

중간 검증에서 테스트 fixture의 제거된 legacy 컬럼 사용, 잘못된 hash 문자,
AssertJ 오버로드 컴파일 오류를 수정한 뒤 전체 빌드를 통과했다.
Gradle의 기존 deprecated API 경고는 남으며 실패는 아니다.

## 남은 검증과 운영 영향

- 사진함 HTTP 업로드·목록·중복 병합 API는 별도 개발 범위다. 이번 검증은 실제 DB와
  fixture/mock·로컬 이미지 HTTP stub을 사용하며 실제 사진함 API 연동 완료를 뜻하지 않는다.
- 발행본 보호는 restrictive FK fixture로 검증했다. #24의 실제 번들 스키마·callback을
  연결한 뒤 생성→발행→Photo 삭제 흐름을 dev에서 통합 검증해야 한다.
- 실제 Cloudinary 계정·iOS SDK·WebView·사진 선택 수동 검증은 해당 환경이 준비되지 않아 미실행이다.
- 기존 public ID/URL이 불완전하거나 충돌하면 V5는 안전하게 실패한다. 실제 배포 전 데이터를 점검한다.
- 프로세스 종료·외부 삭제 실패 시 파일이 남을 수 있다. 식별자 로그를 이용한 수동 정리가 필요하다.
  공개 ID는 재사용하지 않고 공유 파일은 기존 ImageAsset FK로 연결한다.
- PR에는 확정 번들 Spec·구현 계획·PRD를 함께 포함한다. 원본 문서 작업 공간은 보존했다.
  본 작업은 dev 대상 PR까지이며 머지·배포는 수행하지 않는다.
- 변경 파일: `image/**`, `photo/**`, `V5__image_photo_foundation.sql`,
  `Member.kt`, `Bunch.kt`, `RegistrationService.kt`, `BunchService.kt`,
  기존 이미지/가입/초대/본인 프로필 테스트 4개, 신규 image/photo 테스트 3개,
  README와 로그인·온보딩 계약, 사진함 기반 계약·구현 계획·이 검증 문서,
  확정 번들 Spec·번들 구현 계획·PRD의 우선순위 안내.
- 정확한 토큰 사용량: 확인 불가.
