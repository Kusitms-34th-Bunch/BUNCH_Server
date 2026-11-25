# 사진함 기반 영속성 계약

이 문서는 이슈 #23의 공용 이미지 자산, Photo, 공동 업로더 영속성 계약과
번들 도메인이 사용할 연결·삭제 경계를 고정한다. 사진함 업로드·목록·중복 병합
HTTP API는 이 범위에 포함하지 않는다. 업로드 저장 규칙은 [사진함 업로드 계약](photo-box.md)을 따른다.

## 기준 문서

제품 정책은 [PRD](../PRD.md)와 [번들 Spec](bundle.md)을 따른다.
역할·이탈·번치 삭제의 확정 계약과 기능별 API·저장 경계는 [번치 Spec 색인](bunch.md)을 따른다.
이 문서는 #23의 영속성·연결 계약을 구체화한다. 기존 인증·가입은
[로그인·온보딩 API 계약](login-onboarding-api.md)을 따른다.

## 모델

### ImageAsset

`com.bunch.image.domain.ImageAsset`는 외부 이미지 파일의 공용 DB 자산이다.

| 필드 | 타입·제약 |
| --- | --- |
| `id` | `Long`, identity, 기본값 `0` |
| `objectKey` | `String`, 필수·유일. R2 객체 경로만 저장하며 서명 URL은 저장하지 않는다 |
| `createdAt` | `Instant`, 기본값 `Instant.now()` |

`ImageAssetService.store`는 소유자 변경 트랜잭션 안에서만 호출한다.
저장소 삭제는 `releaseAfterCommit(assetId)`로 요청하며, R2 객체 삭제는 DB 변경이
성공적으로 커밋된 뒤에만 시도한다. 기존 ImageAsset ID와 소유 FK는 유지한다.

### Photo

`width`·`height`는 EXIF 회전 뒤 보이는 원본의 픽셀 크기다. 신규 업로드 검사 결과를 저장하며
두 값 모두 양수이거나 둘 다 `null`이다. 기존 사진은 V56에서 `null`로 유지하고 직접 이전 때 채울 수 있다.
썸네일 크기는 저장하지 않는다. 썸네일 생성은 원본 `width`·`height`와 원본 바이트를
변경하지 않는다.


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
| `capturedUtcOffset` | 선택 `String` `±HH:mm`, varchar(6). 촬영 현지 시각의 UTC 오프셋. 서버는 `-12:00`~`+14:00`을 검사하고 DB CHECK는 모양만 검사한다. 없으면 추천에서 `Asia/Seoul`로 본다 |
| `capturedMillis` | 선택 `Int` `0`~`999`. 촬영 시각의 초 아래 부분(F7, R1). `capturedTime`이 없으면 가질 수 없고(DB CHECK), 날짜를 비우면 함께 지운다. 없으면 모름이다(`0`이 아니다) |
| `width`, `height` | 선택 `Int` 양수. 저장된 이미지의 픽셀 크기(R1의 해상도). 서버가 업로드 때 이미지에서 읽고, 둘이 함께 있거나 함께 없다(DB CHECK). 이전에 올린 사진은 없다 |

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
앱이 줄이기 전 원본 파일의 SHA-256(`originalFileHash`, 선택, `original_file_hash`)은 같은 번치에서 값이 있는
행끼리 유일하며(부분 유니크), 이미지 지문이 가까울 때만 같은 사진의 근거로 쓴다([사진함](photo-box.md)).
해시 계산·중복 병합 API는 사진함 담당자의 범위다. 촬영 날짜·시각은 앱이 전달한 현지 값을
시간대 재변환 없이 보관한다. 시각이 없으면 업로드 시각으로 대체하지 않는다.

PostgreSQL의 `captured_time TIME`은 `time(6)` 정밀도 계약이다. 앱 입력은 초 단위
정밀도를 기준으로 하며, Java `LocalTime`의 나노초 값을 임의로 넣어 DB에서 원문
그대로 보존된다고 가정하지 않는다. 마이크로초보다 세밀한 값은 PostgreSQL 저장
과정에서 표현되지 않을 수 있다.

### PhotoRecoFeature

추천 번들이 쓰는 사진의 특성이다(추천 번들 조합 명세 4-5). 사진당 1행이고 키는 `photo_id`이며,
Photo가 지워지면 DB가 함께 지운다(`photo_image_fingerprints`와 같은 보조 테이블 방식). 원본 유형, 품질, AI 분석 상태가 있고
AI 분석의 모델 버전·응답 원문·분석 시각 컬럼은 자리만 있다(V48, 분석을 만드는 이슈가 채운다).

| 필드 | 타입·제약 |
| --- | --- |
| `photoId` | `Long`, PK, `photos.id` 참조 |
| `source` | 선택 `CAMERA`, `SCREENSHOT`, `SAVED`, varchar(16). 비어 있으면 원본 유형을 모른다 |
| `quality` | 선택 `GOOD`, `NORMAL`, `LOW`, varchar(8). 비어 있으면 품질을 계산하지 못했다 |
| `aiStatus` | 선택 `PENDING`, `DONE`, `FAILED`, varchar(16). 비어 있으면 분석을 요청한 적이 없다 |
| `ai_model_version`, `ai_raw`, `analyzed_at` | 선택. 엔티티가 아직 갖지 않는다(분석을 만들 때 매핑한다) |

- 새 사진은 항상 행을 만든다. 값은 업로드 요청의 `sourceType`에서 온다(`SAVED_IMAGE`는 `SAVED`로 바꾸고 `UNKNOWN`은 비운다).
- 이 테이블을 만들기 전에 올라온 사진은 행이 없다. 행이 없는 것은 값이 비어 있는 것과 같다.
- 합쳐지거나 다시 올라온 사진은 `source`가 비어 있을 때만 새 값으로 채운다(행이 없으면 값이 있을 때 만든다). 있는 값은 바꾸지 않는다.
- `quality`는 새 사진을 저장할 때 한 번 계산해 채운다(`ImageQuality`). 이미지를 읽을 수 없으면 비우고 업로드는 성공한다. 같은 사진이 합쳐질 때는 다시
  계산하지 않고, 있는 값은 바꾸지 않는다. 기준은 조합명세 4-4 표이고 `CombinationSettings.quality`에 있다.

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
  발행본은 `bundle_photos.photo_id`만 null로 만들고 필수 `ImageAsset` 참조·순서를
  보존한다. 초안은 해당 번들 사진을 제거하고 순서를 다시 매긴다.
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
- 다른 정리 오류는 이미 성공한 owner transaction을 실패로 보고하거나 rollback하지
  않는다.
- 자산 DB 행 삭제가 실제로 커밋된 뒤에만 `storage.delete(objectKey)`를 호출한다.
- 외부 파일 삭제 실패는 owner 변경을 되돌리지 않는다.

이 방식은 미래 retaining table을 ImageAsset에 restrictive FK로 연결하기만 해도
참조 카운터나 테이블 목록을 수정하지 않고 파일 보존을 적용한다. 새 업로드는
새 UUID object key를 생성하며 삭제한 key로 자산을 재생성하거나 파일을 덮어쓰지 않는다.
공유 파일은 기존 자산 ID의 FK로 연결한다. 애플리케이션 URL 값 캐시·URL 갱신 API·정리
배치는 추가하지 않는다.

## 기존 프로필 이전

R2 이전은 별도 준비 SQL과 전환 Flyway로 나눈다. 준비 단계는 nullable `object_key`를
추가하고 기존 자산 ID·FK를 보존하며, 전환 단계는 누락 key를 거부한 뒤 기존 URL·public ID
저장을 제거한다. 한쪽만 있는 자산이나 key 충돌은 전체 전환을 실패시키고 기존 스키마·데이터를
보존한다. 프로필은 nullable `profile_image_asset_id`를 계속 사용하고 기본 이미지는
FK와 API `imageUrl`이 모두 `null`이다. 세부 순서와 복구는 [R2 이미지 이전 문서](../r2-migration.md)를 따른다.

프로필의 JPEG·정사각형·크기·메타데이터 제거 검증은 기존 저장소 처리를 유지하며,
저장 객체는 `profiles/<UUID>/profile.jpg` 한 개다.
사진함의 일반 이미지 입력에는 프로필 검증 규칙을 적용하지 않는다. 이미지 저장만
실패하면 가입은 기본 이미지로 완료하고, 번치 변경 실패 시 기존 이미지를 유지한다.

## 사진함·프로필 이미지 저장소

최종 런타임 저장소는 비공개 R2 하나다. 설정 계약은 `R2_ENDPOINT`(버킷명 제외),
`R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`이며 공개 `r2.dev`, Custom Domain,
외부 URL 호환 계층은 사용하지 않는다. DB의 `ImageAsset`에는 `objectKey`만 저장하고
기존 자산 ID와 FK를 유지한다.

객체 경로는 다음과 같이 고정한다.

- 사진: `photos/<UUID>/original.jpg`와 같은 디렉터리의 `thumbnail.jpg`.
- 프로필: `profiles/<UUID>/profile.jpg` 한 객체. 기존 프로필 JPEG·정사각형·1024px·5MiB·메타데이터
  제거 규칙은 유지한다.

### 입력 검증과 메모리 경계

`PhotoImageStorage.inspect(file)`는 픽셀을 디코딩하지 않고 JPEG 형식·구조·크기·헤더 해상도·
위치 메타데이터를 검사해 `PhotoImage`를 반환한다. 보관본 바이트는 재인코딩하지 않고 그대로
유지하므로 파일 해시도 유지된다. 사진 입력은 30MiB 이하이고, 헤더가 선언한 긴 변은 4096px
이하여야 하며, EXIF/XMP 위치 정보가 남아 있으면 거부한다. 프로필 입력은 기존 정제 규칙을
그대로 따른다.

기존 검사 범위와 동일하게 IPTC(APP13) 위치 필드와 EXIF에 내장된 미리보기 이미지는 검사하지
않는다. 원본 바이트 보존은 이 메타데이터까지 제거한다는 보장이 아니며, 검사 확장은 별도 정책
확정과 회귀 검증이 필요하다.

`ImageUploadLimiter`는 프로세스 전체에서 두 개의 업로드만 허용한다. `PhotoController`의
`inspect`부터 업로드 완료까지와 `ProfileImageStorage`의 정제부터 R2 PUT까지 같은 경계를
공유하므로 애플리케이션이 보유하는 업로드 바이트의 최대치는 60MiB다. 이는 multipart 요청
파싱 한도나 디스크 임시파일 임계값을 새로 정하는 설정이 아니다. multipart 파싱은 서블릿의
기존 정책과 기본 디스크 임계값을 따르며, permit을 얻기 전에 추가로 전체 바이트를 메모리에
읽지 않는다. 단, 가입 멱등성 fingerprint를 위한 기존 5MiB 이하 프로필 읽기는 예외다.
번치 프로필 저장의 멱등 지문은 전체 파일을 추가로 보관하지 않고 8KiB 버퍼로 해시한다.

`ImageDecodeLimiter`도 프로세스 전체 두 permit을 공유하며 fingerprint, 품질 계산, 앱 입력
축소, 프로필 정제, 사진 썸네일 생성을 함께 제한한다. 새 이미지 라이브러리는 추가하지 않는다.

### 썸네일과 업로드

`PhotoImageStorage.upload`는 DB transaction 밖에서 다음을 한 번 수행한다.

1. ImageIO로 EXIF 방향을 반영해 원본 비율과 전체 영역을 유지하는 fit 썸네일을 만든다.
   긴 변은 최대 1080px이고 작은 이미지는 확대하지 않으며 JPEG 품질은 85다. 예를 들어
   1920x2560 원본은 810x1080 썸네일이 된다. 원본 바이트와 원본 `width`·`height`는
   변경하지 않는다.
2. 원본을 `original.jpg`로, 방금 만든 썸네일을 `thumbnail.jpg`로 각각 서로 다른 object key에
   `Cache-Control: private, max-age=3600` metadata와 함께 R2에 PUT한다.

썸네일은 큐나 별도 비동기 작업으로 만들지 않는다. JPEG 전체를 대상으로 ImageIO의 bounded
source subsampling을 적용해 디코딩한 뒤 원본 비율을 유지해 전체 영역을 최종 크기로 줄인다.
중앙 crop은 하지 않으며, 원본 보관본의 바이트와 해시는 이 과정에서 바뀌지 않는다. 두 PUT 중
하나라도 실패하면 생성한 사진 객체를 보상 정리하고 업로드 실패로 처리한다.

프로필은 `ProfileImageStorage`가 기존 JPEG 정제 결과를 `profiles/<UUID>/profile.jpg`로
같은 `Cache-Control: private, max-age=3600` metadata와 함께 PUT한다. 사진·프로필 모두 외부
저장소 I/O는 DB transaction 밖에서 실행하며, DB 자산 행 삭제와 R2 객체 삭제는 커밋 후에만
시도한다. restrictive FK가 공유 자산을 보호한다.

### 표시 URL

표시 URL은 `ImageUrls.full(key)`, `ImageUrls.thumbnail(key)`, `ImageUrls.profile(key?)`가
응답 시 로컬에서 만든다. 모든 요청은 기존 유스케이스의 인증·소속·공개 범위 확인을 매번
통과해야 서명할 수 있다. UTC 현재 시각을 시간 단위로 내림한 `slotStart`를 기준 시각으로
삼고, signed GET의 만료 시각은 `slotStart + 7200초`로 고정한다. 따라서 응답 시점에 남은
유효 시간은 1~2시간이며, 응답 시점부터 항상 2시간인 것은 아니다. 같은 bucket·object key·
credentials·UTC 시간 슬롯에서는 같은 URL을 만들고 다음 UTC 정시부터 URL이 바뀐다.

- 사진함 목록·카드·Feed는 `thumbnail.jpg`를 기존 `thumbnailUrl` 또는 `coverImageUrl` 필드로
  제공한다.
- 사진함·발행 Bundle 상세는 `original.jpg`를 기존 `imageUrl` 필드로 제공한다.
- 프로필·멤버·업로더 이미지는 `profile.jpg`를 기존 `imageUrl` 필드로 제공한다.
- `original.jpg`와 `thumbnail.jpg`는 서로 다른 key이므로 각각 요청된 key로 서명하며 URL을
  서로 재사용하지 않는다. 기존 필드명과 `null`/기본 이미지 fallback을 유지하고 `objectKey`는
  외부 응답에 내보내지 않는다.
- 비공개 버킷은 유지한다. signed GET에는 `response-cache-control=private, max-age=3600`을
  덮어쓰기 파라미터로 넣으므로 기존 객체 metadata를 다시 쓸 필요가 없다. 신규 PUT metadata는
  위 Cache-Control을 사용한다. URL 만료는 새 요청 URL의 유효성만 끝내며 이미 캐시된 바이트를
  자동으로 회수·폐기하지 않는다.
- 기존 정사각형 `thumbnail.jpg` 교체 시 이전 캐시와 구분하기 위해 썸네일 signed GET에만
  `response-content-disposition=inline; filename="thumbnail-fit.jpg"`를 추가한다. 원본·프로필
  signed URL에는 이 override를 추가하지 않는다.
- 서명 URL 자체는 DB·Redis·멱등성 결과·이벤트에 저장하거나 재생하지 않는다. 멱등 성공 재생도
  저장된 object key로 현재 권한을 다시 확인한 뒤 현재 UTC 시간 슬롯의 signed URL을 만든다.
  URL이 만료되면 전용 refresh API를 만들지 않고 같은 기존 조회 API를 다시 호출한다.

세부 이전 순서와 복구는 [R2 이미지 이전 문서](../r2-migration.md)를 따른다. 준비 단계와
전환 Flyway는 분리하고, 이미 적용된 Flyway는 수정하지 않는다.

### 저장소 테스트

저장소 테스트는 DB와 실제 R2 없이 로컬 stub으로 R2 PUT·부분 실패 보상·삭제 경계를 검증한다.
입력 30MiB/헤더 4096px 경계, EXIF 방향·위치 메타데이터, 두 limiter의 동시성, 전체 JPEG를
bounded source subsampling으로 읽는 원본 비율 유지·전체 영역 fit 썸네일, 긴 변 최대 1080px·확대 없음·
JPEG 품질 85·1920x2560→810x1080, 원본 바이트·`width`·`height` 불변, object key 경로와 UTC 시간 슬롯
기준의 deterministic signed GET·`response-cache-control`·신규 PUT Cache-Control·썸네일 전용
`response-content-disposition` 계약을 포함한다.

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

## 번치 이탈·삭제 연계

- 소속 종료·사진 업로더 제거·초안 참여/코멘트 정리는 같은 트랜잭션에서 처리한다.
  하나라도 실패하면 소속·역할·초대 변경까지 롤백한다.
- 생성자의 번치 이탈은 본인 초안 삭제다. 다른 참여자 이탈은 초안 참여·코멘트만
  정리하며 공유 Photo의 다른 업로더와 발행본 이미지·기여 기록은 보존한다.
- 직접 나가기·내보내짐 후 재참여는 새 일반 멤버 소속이다. 삭제된 Photo·업로더·초안·초안
  코멘트와 예전 관리자 권한은 복구하지 않는다. 남은 발행본의 동일 계정 이름·프로필·
  코멘트 연결만 현재 소속을 기준으로 다시 표시한다.
- 번치 영구 삭제 전 프로필·사진함·발행 이미지 자산 ID를 수집하고 DB 참조 종료와
  `releaseAfterCommit` 등록을 같은 트랜잭션에서 처리한다. 외부 파일 삭제는 커밋 후 수행하고
  남은 restrictive FK 참조가 있으면 공유 자산을 유지한다. 삭제한 번치는 재참여로 복구하지 않는다.
- 삭제자 외 멤버에게는 본체 FK 없는 접근 안내와 기기별 푸시 대기만 남긴다.
  직접 나가기·직접 삭제에는 본인 안내를 만들지 않는다. 안내는 확인 또는 30일 만료로 제거한다.
