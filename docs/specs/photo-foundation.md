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
- 기본 `NONE` callback은 #23의 빈 경계다. #24는 실제 bundle 테이블을 도입할 때
  반드시 callback을 연결해 초안 Photo 연결·캡션을 같은 transaction에서 정리해야
  한다. 사진이 0장인 초안은 폐기한다. 발행본은 원본 Photo FK만 해제하고 별도
  ImageAsset FK·순서·캡션을 보존한다. 발행·연결 caller도 같은 Bunch 잠금을 유지해
  삭제가 먼저 커밋되면 연결을 거부하고 발행이 먼저 커밋되면 파일을 보존한다.
  #24 연동 전까지는 bundle 테이블이나 가상 registry를 만들지 않는다.
- Photo 삭제가 확정된 뒤 `ImageAssetService.releaseAfterCommit(assetId)`를
  transaction 내부에서 호출한다.

최종 Photo 삭제 callback은 `@Transactional` 경계에서 실행된다. #24 callback은
그 경계를 `MANDATORY`로 요구해 Photo 삭제와 bundle 정리를 별도 transaction으로
분리하지 않는다. 반복 삭제는 callback과 이미지 해제를 다시 적용하지 않는다.

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

## 사진함 이미지 저장소

사진함의 이미지 파일은 `com.bunch.global.image.PhotoImageStorage`가 `photos/` 경로로
업로드·삭제한다. 프로필의 `ProfileImageStorage`(JPEG·정사각형·1024px·5MB·`profiles/`)는
바꾸지 않으며 사진함에 프로필 규칙을 적용하지 않는다. 사진함 업로드 API와 해시 계산·중복
병합은 #30의 범위이고, 이 저장소는 #30이 쓸 부품만 제공한다.

### 입력 검증

`inspect(file)`는 업로드 바이트를 디코딩하지 않고 검사해 `PhotoImage`(바이트, 가로, 세로)를
반환한다. 바이트는 재인코딩하지 않고 받은 그대로 돌려주므로 호출자는 이 바이트로 SHA-256을
한 번만 계산하고, 같은 사진이 아닐 때만 `upload`를 호출한다.

| 항목 | 규칙 | 거부 사유 |
| --- | --- | --- |
| Content-Type | `image/jpeg`만 허용한다 | `UNSUPPORTED_FORMAT` |
| 크기 | 1장 30MiB(30 × 1024 × 1024바이트) 이하. 선언된 크기와 무관하게 30MiB + 1바이트까지만 읽는다 | `TOO_LARGE` |
| JPEG 구조 | SOI로 시작해 EOI로 끝나며 마커 세그먼트 길이가 파일 안에 있어야 한다. 구조가 깨지면 추측하지 않고 거부한다 | `UNSUPPORTED_FORMAT` |
| 해상도 | 프레임 헤더가 선언한 긴 변이 `photo-image.max-dimension`(기본 4096) 이하. 정사각형 제한은 없다. 픽셀은 디코딩하지 않으므로 작은 파일이 거대한 래스터를 선언해도 메모리를 쓰지 않는다 | `RESOLUTION_EXCEEDED` |
| 위치 메타데이터 | EXIF의 GPS IFD 포인터(0x8825)나 XMP의 위치 속성(`exif:GPS*`, `Iptc4xmpCore:Location`, `Iptc4xmpExt:LocationCreated`·`LocationShown`, `photoshop:City`·`State`·`Country`)이 남아 있으면 거부한다 | `LOCATION_METADATA` |

모든 거부는 `PhotoImageRejectedException`(`ErrorCode.INVALID_INPUT`)이며 `reason`으로
사유를 구분한다. 같은 파일을 다시 보내도 성공할 수 없으므로 #30은 재시도 불가로 응답하고,
사유별 사진함 오류 코드는 #30이 정한다.

- 명세서 3.2.2는 앱이 위치 메타데이터(EXIF·XMP 포함)를 지우고 긴 변 2560px로 줄여 올린다고
  정한다. 서버는 지우지 않고 남아 있으면 거부한다. 파일을 다시 인코딩하면 30MB 이미지를
  통째로 디코딩해야 하고 해시도 달라지기 때문이다.
- IPTC(APP13) 위치 필드와 EXIF 안에 내장된 미리보기 이미지는 검사하지 않는다.
- 픽셀을 디코딩하지 않으므로 JPEG 헤더만 정상이고 데이터가 손상된 파일은 이 단계에서 걸러지지
  않는다. 외부 저장소가 처리하지 못하면 업로드 실패로 나타난다.
- 허용 형식은 현재 JPEG뿐이다. 명세서 3.2.2는 앱이 JPEG·PNG·HEIC·WebP를 고를 수 있다고 하므로
  앱이 서버로 JPEG만 보내는지 확정 대기다. HEIC·WebP를 서버가 받으려면 새 의존성이 필요하다.

### 업로드와 삭제

- `upload(image)`는 `photos/{UUID}` public ID로 `overwrite=false` 업로드한다. 응답의 HTTPS URL,
  public ID, `resource_type=image`, `format=jpg`, 가로·세로가 보낸 값과 같은지 확인한다.
- 업로드가 실패하거나 응답이 맞지 않으면 생성한 public ID의 삭제를 시도하고
  `PHOTO_IMAGE_STORAGE_FAILED`(`PHOTO_007`, 503)로 응답한다. 프로필의 `IMAGE_001`과 구분한다.
- 연결 3초, 읽기 `photo-image.read-timeout-seconds`(기본 60초)로 30MB 업로드를 허용한다.
  리다이렉트는 따라가지 않는다. 자격 증명이 없거나 업로드 주소가 안전하지 않으면 원격 호출 없이
  실패하며 애플리케이션 기동은 막지 않는다.
- `delete(publicId)`는 예외를 던지지 않는다. 이미 없는 파일은 성공으로 보고 실패하면 public ID만
  로그에 남긴다. 실패한 삭제의 재시도는 별도 이슈(이미지 파일 삭제 재시도)에서 다룬다.
- 요청마다 최대 30MiB를 서버 메모리로 읽는다. 동시 업로드 한도와 컨테이너 메모리는 운영 환경
  점검에서 정한다.

### 표시 URL

`PhotoImageUrls`는 저장된 `ImageAsset.url`에서 표시용 주소를 계산하며 따로 저장하지 않는다.
원본과 썸네일 파일을 따로 두지 않는다(PRD §7 확정 사항).

- 전체 크기: 저장된 URL 그대로다. 보관된 이미지가 곧 전체 크기다.
- 정사각 썸네일: URL의 `/image/upload/` 바로 뒤에 `c_fill,w_N,h_N/` 변환을 넣는다. 기본 300,
  허용 범위 1~2560이다. 변환할 수 없는 URL(업로드 구간이 없는 주소)은 그대로 돌려준다.

### 설정

| 키 | 기본값 | 의미 |
| --- | --- | --- |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | 6MB / 7MB (이 이슈에서 바꾸지 않음) | 서버 전체 업로드 한도. 30MB 사진을 받으려면 #30의 업로드 API와 함께 30MB / 31MB로 올린다. 모든 파일 업로드에 적용되므로 프로필·번치 이미지 담당과 배포 담당의 확인이 필요하다. 프로필 저장소는 코드에서 5MB 검사를 유지한다 |
| `photo-image.max-dimension` | 4096 | 허용하는 긴 변 상한(px) |
| `photo-image.read-timeout-seconds` | 60 | 외부 저장소 응답 대기 시간 |
| `cloudinary.*` | 기존 설정 | 프로필 저장소와 같은 자격 증명을 쓴다 |

### 저장소 테스트

`JpegHeaderInspectorTest`, `PhotoImageStorageTest`, `PhotoImageUrlsTest`는 DB와 실제 소켓 없이
실행한다. 외부 저장소 호출은 `MockRestServiceServer`로 대체해 업로드 요청 형식, 응답 검증 실패 시
정리 호출, 삭제, 설정 주입을 검증한다. 경계값(30MiB, 긴 변 4096px)과 위치 메타데이터 거부도 포함한다.

### 확정 대기

- 이미지 접근 방식: 현재는 누구나 주소로 볼 수 있는 공개 URL이다. PRD §7-13은 권한 확인 후
  짧은 유효기간의 URL을 권장하고 명세서에는 이 방식이 없다. 사진함 목록 API(#35)를 확정하기
  전에 정한다.
- 서버가 받는 형식(위 허용 형식), 해상도 상한 값, 썸네일 크기, 위치 메타데이터를 거부할지
  지울지는 앱의 처리 범위 답에 따라 바뀔 수 있다.
- 사용 중인 Cloudinary 플랜의 30MB 업로드 가능 여부는 확인하지 못했다.

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
