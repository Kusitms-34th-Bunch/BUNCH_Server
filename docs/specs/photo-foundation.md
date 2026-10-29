# 사진함 기반 영속성 계약

이 문서는 이슈 #23의 공용 이미지 자산, Photo, 공동 업로더 영속성 계약과
번들 도메인이 사용할 연결·삭제 경계를 고정한다. 사진함 업로드·목록·중복 병합
HTTP API는 이 범위에 포함하지 않는다.

## 기준 문서

제품 정책은 [PRD](../PRD.md)와 [번들 Spec](bundle.md)을 따른다.
이 문서는 #23의 영속성·연결 계약을 구체화한다. 기존 인증·가입은
[로그인·온보딩 API 계약](login-onboarding-api.md)을 따른다.

## 모델

### ImageAsset

`com.bunch.image.domain.ImageAsset`는 외부 이미지 파일의 공용 DB 자산이다.

| 필드 | 타입·제약 |
| --- | --- |
| `id` | `Long`, identity, 기본값 `0` |
| `publicId` | `String`, 필수·유일 |
| `url` | `String`, 필수 |
| `createdAt` | `Instant`, 기본값 `Instant.now()` |

`ImageAssetService.store`는 소유자 변경 트랜잭션 안에서만 호출한다.
저장소 삭제는 `releaseAfterCommit(assetId)`로 요청하며, 외부 파일 삭제는 DB 변경이
성공적으로 커밋된 뒤에만 시도한다.

### Photo

Photo는 번치 사진함의 한 기록이다. 실제 파일은 `ImageAsset`가 가리키며, Photo가
그 자산을 소유한다.

| 필드 | 타입·제약 |
| --- | --- |
| `id` | `Long`, identity, 기본값 `0` |
| `bunch` | 필수 `ManyToOne`, `photos.bunch_id` |
| `imageAsset` | 필수 `ManyToOne`, `photos.image_asset_id` |
| `fileHash` | 필수 `String`, 소문자 SHA-256 64자리 hex |
| `processingStatus` | `PENDING`, `READY`, `FAILED`, varchar(16) |
| `capturedDate` | 선택 `LocalDate` |
| `capturedTime` | 선택 `LocalTime`, 날짜가 있을 때만 허용 |
| `dateSource` | `EXIF`, `USER`, `UNKNOWN`, varchar(16) |
| `createdAt` | 필수 `Instant`, `timestamptz` |

날짜 출처의 저장 계약은 다음과 같다.

- `UNKNOWN`이면 `capturedDate`와 `capturedTime`이 모두 null이다.
- `EXIF` 또는 `USER`이면 `capturedDate`가 필수다.
- `capturedTime`은 `capturedDate`가 있을 때만 존재할 수 있다.

DB CHECK와 `Photo` 생성자에서 같은 규칙을 적용한다. `fileHash`는
`[0-9a-f]{64}`만 허용하며 대문자·구분 기호·다른 길이는 거부한다. 같은 번치에서
동일한 파일 해시는 하나의 Photo로만 저장한다. 파일 해시는 업로드 입력 바이트의
SHA-256이며 전역 중복 제거 키가 아니다. 파일명 변경은 해시에 영향을 주지 않지만
재압축·리사이즈·EXIF 변경으로 바이트가 달라지면 다른 파일로 판별한다. 서버가 실제
업로드 바이트로 계산하며 클라이언트가 보낸 해시만으로 중복을 확정하지 않는다.
해시 계산·중복 병합 API는 사진함 담당자의 범위다. 촬영 날짜·시각은 앱이 전달한 현지 값을
시간대 재변환 없이 보관한다. 시각이 없으면 업로드 시각으로 대체하지 않는다.

PostgreSQL의 `captured_time TIME`은 `time(6)` 정밀도 계약이다. 앱 입력은 초 단위
정밀도를 기준으로 하며, Java `LocalTime`의 나노초 값을 임의로 넣어 DB에서 원문
그대로 보존된다고 가정하지 않는다. 마이크로초보다 세밀한 값은 PostgreSQL 저장
과정에서 표현되지 않을 수 있다.

### PhotoUploader

Photo와 회원의 업로드 이력은 별도 행으로 관리한다. 같은 Photo에 여러 회원이
연결될 수 있다.

| 필드 | 타입·제약 |
| --- | --- |
| `id` | `Long`, identity, 기본값 `0` |
| `photo` | 필수 `ManyToOne` |
| `member` | 필수 `ManyToOne` |
| `uploadBatchId` | 필수 `UUID` |
| `uploadedAt` | 필수 `Instant`, `timestamptz` |

`(photo_id, member_id)`는 `uk_photo_uploader`로 유일하다. 같은 사진을 다른 회원이
올리면 같은 번치의 Photo를 재사용하고 그 회원의 업로더 연결을 추가한다. 동일 회원의
중복 요청은 업로더 행을 중복 생성하지 않는다. `uploadBatchId`는 앱에서 한 번에
선택·업로드한 묶음을 식별하고 `uploadedAt`은 서버 업로드 시각이다.

## 조회·소속 검증

`PhotoService.findReadyPhotosForBunch`는 호출자가 제시한 Photo ID 목록을 요청
순서로 반환한다. 요청 목록이 비어 있으면 빈 목록을 반환한다. 비어 있지 않은
요청에서는 다음 조건을 모두 만족해야 한다.

- 존재하지 않는 Photo가 없어야 한다.
- 모든 Photo가 요청한 Bunch에 속해야 한다.
- 모든 Photo의 `processingStatus`가 `READY`여야 한다.
- 중복 ID·0 이하 ID는 거부한다.

반환 엔티티의 지연 로딩 연관은 caller의 읽기 transaction 안에서 DTO로 변환한다.
업로더 전체는 `PhotoUploaderRepository.findAllByPhotoIdInOrderByPhotoIdAndIdAsc`로
조회한다. 현재 소속 여부와 최초 번들 참여자 합집합 계산은 번들 caller가 검사한다.

하나라도 실패하면 일부 결과를 반환하지 않고 전체 요청을 거부한다. 이 경계에는
무제한 Bunch 목록 조회 쿼리를 제공하지 않는다. 사진함 목록과 HTTP API는 별도
구현에서 이 계약을 사용한다.

## 연결 잠금 계약

번들 생성·사진 연결 같은 caller는 자신의 write transaction 안에서
`PhotoService.lockReadyPhotosForConnection`을 호출해야 한다. 이 메서드는
`Propagation.MANDATORY`이므로 독립 호출로 사용할 수 없다.

잠금 순서는 항상 다음과 같다.

1. `BunchRepository.findLocked(bunchId)`로 Bunch를 잠근다.
2. 요청한 Photo를 ID 오름차순으로 `PESSIMISTIC_WRITE` 잠근다.
3. 누락·타 Bunch·`READY`가 아닌 Photo가 있으면 전체 연결을 거부한다.

Photo 잠금은 batch 조회 경로 하나로 통일한다. 반환 직후 transaction이 끝나면
잠금도 해제되므로, caller는 반환된 엔티티를 사용하는 연결·검증·저장을 같은
write transaction에서 완료해야 한다.

## 공동 업로더 제거와 최종 삭제

`PhotoService.removeUploader`는 Bunch를 먼저 잠근 뒤 해당 Photo를 ID 오름차순
batch 잠금으로 확보하고, 그 Photo의 업로더 행을 ID 오름차순으로 잠근다.

이 메서드는 사진함 내부의 연결 경계다. actor가 해당 작업을 수행할 권한이 있는지,
현재 회원이 Bunch에 소속되어 있는지는 caller가 검증하고, 이 서비스는 전달된
`memberId`의 업로더 연결만 제거한다.

- 다른 업로더가 남아 있으면 Photo를 유지한다.
- 마지막 업로더를 제거할 때 `PhotoFinalDeletionCallback`을 Photo 삭제 직전에
  같은 transaction에서 호출한다.
- callback이 예외를 던지면 업로더 제거와 Photo 삭제 모두 rollback한다.
- 기본 `NONE` callback은 #23에서 사진함만 사용할 때의 빈 경계다. #24는
  `BundlePhotoDeletionCallback`을 `PhotoFinalDeletionCallback`으로 등록하고,
  `beforePhotoDelete(photoId)`를 같은 transaction에서 `BundleService.beforePhotoDelete`
  로 전달한다. callback은 `MANDATORY` transaction을 요구하므로 Photo 삭제와 번들
  정리를 분리하지 않는다.
- 최종 업로더 제거가 확정되기 전에 callback이 번들 사진 참조를 정리한다.
  발행본은 `bundle_photos.photo_id`만 null로 만들고 필수 `ImageAsset` 참조·순서·캡션을
  보존한다. 초안은 해당 번들 사진과 사진별 편집권을 제거하고 순서를 다시 매긴다.
  남은 사진이 없으면 초안을 영구 삭제한다. 발행·연결 caller도 같은 Bunch 잠금을
  유지해 삭제가 먼저 커밋되면 연결을 거부하고 발행이 먼저 커밋되면 파일을 보존한다.
- callback 또는 그 안의 번들 정리가 예외를 던지면 업로더 제거·Photo 삭제·번들 정리를
  모두 rollback한다. Photo 삭제가 확정된 뒤에만 `ImageAssetService.releaseAfterCommit(assetId)`를
  요청하며, 외부 파일 삭제는 DB 변경 커밋 후에 시도한다.

최종 Photo 삭제 callback은 `@Transactional` 경계에서 실행된다. #24 callback은
그 경계를 `MANDATORY`로 요구해 Photo 삭제와 bundle 정리를 별도 transaction으로
분리하지 않는다. 이미 제거된 업로더·Photo에는 callback을 다시 적용하지 않는다.

## 이미지 자산 보호와 파일 정리

`photos.image_asset_id`는 restrictive FK로 보호한다. 현재 V5는
`ON DELETE RESTRICT`로 선언하며, 이는 Photo가 참조 중인 ImageAsset에 대한
`NO ACTION` 안전 경계로 취급한다. Photo FK를 cascade로 바꾸지 않는다.

ImageAsset 삭제 시 서비스는 after-commit callback에서 `REQUIRES_NEW` transaction을
열고 자산 행을 ID로 `PESSIMISTIC_WRITE` 잠근 다음 삭제·flush·commit한다. 이
시점에는 Photo, 회원 프로필, Bunch 프로필 또는 미래의 bundle retaining table이
restrictive FK로 참조 중인지 DB가 최종 판단한다.

- FK SQLSTATE `23503`이면 자산을 보존하고 외부 파일을 삭제하지 않는다.
- 다른 정리 오류는 자산 ID만 로그에 남긴다. 이미 성공한 owner transaction은
  실패로 보고하거나 rollback하지 않는다.
- 자산 DB 행 삭제가 실제로 커밋된 뒤에만 `storage.delete(publicId)`를 호출한다.
- 외부 파일 삭제 실패는 owner 변경을 되돌리지 않는다.

이 방식은 미래 retaining table을 ImageAsset에 restrictive FK로 연결하기만 해도
참조 카운터나 테이블 목록을 수정하지 않고 파일 보존을 적용한다. 새 업로드는
새 public ID를 생성하며 삭제한 public ID로 자산을 재생성하거나 파일을 덮어쓰지 않는다.
공유 파일은 기존 자산 ID의 FK로 연결한다. 파일 삭제 실패는 public ID만 기록하며
자동 재시도·정리 배치는 추가하지 않는다.

## 기존 프로필 이전

V5는 members·bunches의 기존 public ID/URL 쌍을 검사하고 같은 public ID가 같은
URL을 가리킬 때 하나의 ImageAsset으로 백필한다. 한쪽만 있는 쌍이나 URL 충돌은
전체 마이그레이션을 실패시키고 기존 스키마·데이터를 보존한다. 재업로드 없이
nullable `profile_image_asset_id`를 연결한 뒤 직접 저장 컬럼을 제거한다. 기본 이미지는
FK가 null이고 API `imageUrl`도 null이다. 기존 URL 문자열은 그대로 응답한다.

프로필의 JPEG·정사각형·크기·메타데이터 제거 검증은 기존 저장소 처리를 유지한다.
사진함의 일반 이미지 입력에는 프로필 검증 규칙을 적용하지 않는다. 이미지 저장만
실패하면 가입은 기본 이미지로 완료하고, 번치 변경 실패 시 기존 이미지를 유지한다.

## 테스트 기준

Photo 통합 테스트는 `@SpringBootTest`, `@ActiveProfiles("test")`와 기존
`BunchIntegrationSupport` fixture를 사용한다. 실제 PostgreSQL에서 다음을 검증한다.

- 요청 순서 보존과 누락·타 Bunch·`FAILED` Photo 전체 거부
- caller write transaction 필수와 Bunch → Photo ID 오름차순 잠금 계약
- 여러 업로더 제거, callback 실패 rollback, 동시 최종 제거의 callback·파일 삭제
  1회 보장
- 날짜·시각·처리 상태·해시, upload UUID와 `Instant`의 DB 왕복
- ImageAsset restrictive FK와 commit 후 외부 파일 삭제 경계

사진함 HTTP 업로드·목록·중복 병합 테스트는 이 계약 테스트에 포함하지 않는다.
