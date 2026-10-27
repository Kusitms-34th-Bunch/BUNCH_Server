# 번들 REST·STOMP 클라이언트 계약

번들 생성·공동 편집·발행 클라이언트가 사용하는 HTTP REST와 WebSocket/STOMP
프로토콜을 정의한다. 번들 제품 정책은 [번들 Spec](bundle.md)을 따르고, 이 문서는
서버에 실제 등록된 경로·DTO·상태·오류·실시간 메시지 형태를 고정한다.

사진함 업로드·목록·중복 병합 HTTP API는 이 계약에 포함하지 않는다. 테스트 fixture의
Photo ID는 사진함 API의 응답이나 사진함 연동 완료를 의미하지 않는다.

## 공통 규칙

### URL과 응답

REST 기본 경로는 다음과 같다.

```text
/api/v1/bunches/{bunchId}/bundles
```

JSON 필드명은 Kotlin DTO의 camelCase를 사용한다. 날짜는 `yyyy-MM-dd`, 시각은
ISO-8601 UTC offset을 포함한 `Instant` 문자열을 사용한다. enum 값은 대문자 문자열이다.

모든 REST 응답은 기존 공통 envelope를 사용한다.

```json
{
  "success": true,
  "data": {},
  "error": null
}
```

실패 응답은 HTTP 상태 코드와 함께 다음 형태다. `detail`은 오류에 따라 생략 가능한
현재 서버 상태이며, 인증 토큰·세션·서버 connectionId는 포함하지 않는다.

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "BUNDLE_003",
    "message": "error.bundle_version_conflict",
    "detail": {
      "value": "서버의 현재 제목",
      "version": 3
    }
  }
}
```

정상 HTTP 응답은 기본적으로 `200 OK`다. 삭제와 생성자 본인의 초안 `leave`처럼
반환 데이터가 없는 정상 응답도 `success: true`, `data: null`을 사용한다.

### 인증과 세션

REST 요청은 기존 access JWT를 다음 헤더로 전달한다.

```http
Authorization: Bearer <access-jwt>
```

서버는 JWT의 현재 회원 principal과 `JwtSessionDetails.sessionId`를 사용한다. JWT의
`sid`에 해당하는 세션은 Redis에서 활성 상태여야 하며, 번치 소속과 가입 완료 상태도
각 요청에서 다시 확인한다. WebSocket은 연결 URL이나 query string으로 credential을
전달하지 않는다.

STOMP `CONNECT` frame의 native `Authorization` header에만 같은 Bearer access JWT를
넣는다. 이후 `SEND`, `SUBSCRIBE`, `UNSUBSCRIBE` frame에는 Authorization header를
보내지 않는다. URL query string이 있으면 WebSocket handshake가 `400 Bad Request`로 거부된다.
연결 뒤에도 각 frame에서 같은 Redis 세션이 활성인지 검사한다.

### 공통 헤더

| 헤더 | 형식 | 적용 | 동작 |
| --- | --- | --- | --- |
| `Authorization` | `Bearer <access-jwt>` | 모든 REST 요청 | 활성 access 세션과 번치 소속을 확인한다. |
| `Content-Type` | `application/json` | JSON body가 있는 요청 | DTO로 역직렬화한다. |
| `X-Request-Id` | UUID | `title`, `visibility`, `date`, `comment`, 참여자 변경, `leave`, 사진 이동·캡션, 삭제 | 생략하면 서버가 UUID를 생성한다. 변경 이벤트의 `requestId`로 사용한다. |
| `Idempotency-Key` | UUID | 생성·사진 구성 변경·발행 | 필수다. 같은 작업의 재시도와 중복 발행을 구분한다. |

모든 요청의 `BundleActor`는 인증된 `memberId`와 `sessionId`로만 구성한다. STOMP lease에
보관하는 `connectionId`는 서버가 연결에서 얻는 값이며 client body·header로 받지 않는다.
REST에는 socket ID가 없으므로 초안 제목·날짜·캡션 저장에서 서버가 검증하는 값은
경로의 target, 현재 회원·session, 현재 `leaseToken`, lease 만료 여부와
`expectedVersion`이다. private token과 connectionId는 REST 응답·공개 상태에 포함하지 않는다.

Flyway `V7__bundle_edit_leases.sql`은 적용된 이력으로 보존한다. 이번 변경의 V8 마이그레이션은
V7 파일을 수정하지 않고 별도 migration으로 `bundle_edit_leases.screen_id`를 삭제한다. 새
client 식별자를 추가하지 않는다.

모든 상태 변경 이벤트에는 상관관계 UUID가 있다. `X-Request-Id`를 받는 경로는 헤더가
없을 때 서버가 생성한 UUID를 사용하고, `Idempotency-Key`가 필수인 생성·사진 변경·발행
경로는 그 key 문자열을 이벤트 `requestId`로 사용한다.

### 상태와 버전

번들 상태는 `DRAFT`와 `PUBLISHED`만 존재한다. 초안 저장 body의 `mode`가 서버의
현재 상태와 다르면 저장하지 않는다. 따라서 발행 뒤 늦게 도착한 `mode: "DRAFT"`
저장은 현재 `status: "PUBLISHED"`를 담은 상태 충돌로 거부된다.

버전은 클라이언트가 의미를 해석하는 값이 아니라 서버가 발급하는 불투명한 단조
증가 값이다. 클라이언트는 최신 snapshot·event·저장 응답에 포함된 값을 그대로 다음
요청의 `expectedVersion` 또는 `expectedRevision`으로 사용해야 하며, 새 요청마다
`0`을 가정해서는 안 된다.

| 버전 | 대상 |
| --- | --- |
| `revision` | 번들의 저장된 내용과 상태 변경. 편집권·presence·activity 변경은 증가시키지 않는다. |
| `titleVersion` | 제목 |
| `visibilityVersion` | 공개 범위 |
| `dateVersion` | 번들 날짜 |
| `captionVersion` | 한 `BundlePhoto`의 캡션 |
| `BundleCommentResponse.version` | 한 회원의 코멘트 |

`expectedVersion` 또는 `expectedRevision`이 서버 값과 다르면 변경하지 않고 현재 값과
현재 버전을 오류 `detail`로 반환한다. 같은 값을 다시 보내는 no-op 저장은 해당 필드
버전과 `revision`을 증가시키지 않는다. 클라이언트는 응답에 없는 버전을 추측하지
않는다.

코멘트에는 다음 참여자별 규칙이 적용된다.

- 번들 생성 때 만들어진 초기 참여자에게 `BundleComment` row가 아직 없으면, 그
  참여자의 코멘트 상태는 API상 `content: ""`, `version: 0`으로 암묵적으로 해석한다.
  이는 코멘트 row가 없는 초기 상태에만 적용되는 서버 규칙이다.
- `comments` 배열에 해당 회원의 row가 있으면 그 `BundleCommentResponse.version`을
  사용한다. row가 있다는 이유로 `0`을 넣지 않는다.
- 새 참여자를 추가하면 서버는 빈 코멘트 row를 함께 만들고, 그 초기 version을
  추가 전 `bundle.revision + 1`로 설정한다. 참여자 변경 이벤트와 응답에서는 해당
  row가 `content: ""`과 그 version으로 보인다. 참여자를 제거하면 그 회원의 코멘트
  row를 완전히 삭제한다. 다시 추가할 때는 새 revision에 기반한 version을 발급하므로,
  이전 참여 때의 stale 저장이 통과하지 않는다.
- 코멘트 저장 성공 후 version은 서버 응답 또는 다음 snapshot/event에서 읽어 다음
  저장에 사용한다. 클라이언트는 코멘트 version을 임의로 0 또는 1로 재설정하지 않는다.

## REST API

모든 `{bundleId}`와 `{bundlePhotoId}`는 양의 정수다. 경로의 `photoId`는 사진함 Photo
ID가 아니라 번들 내부 `BundlePhoto.id`다.

### 경로 목록

| method | path | body | 주요 헤더 | 성공 `data` |
| --- | --- | --- | --- | --- |
| `POST` | `/api/v1/bunches/{bunchId}/bundles` | `CreateBundleRequest` | `Idempotency-Key` | `BundleSnapshot` |
| `GET` | `/api/v1/bunches/{bunchId}/bundles?status={status}&page={page}&size={size}` | 없음 | 없음 | `BundlePage` |
| `GET` | `/api/v1/bunches/{bunchId}/bundles/summary` | 없음 | 없음 | `BundleSummary` |
| `GET` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}` | 없음 | 없음 | `BundleSnapshot` |
| `PUT` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/title` | `BundleTextRequest` | 선택 `X-Request-Id` | `BundleSnapshot` |
| `PUT` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/visibility` | `BundleVisibilityRequest` | 선택 `X-Request-Id` | `BundleSnapshot` |
| `PUT` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/date` | `BundleDateRequest` | 선택 `X-Request-Id` | `BundleSnapshot` |
| `PUT` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/comments/me` | `BundleTextRequest` | 선택 `X-Request-Id` | `BundleSnapshot` |
| `POST` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/participants` | `BundleParticipantRequest` | 선택 `X-Request-Id` | `BundleSnapshot` |
| `DELETE` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/participants/{memberId}` | 없음 | 선택 `X-Request-Id` | `BundleSnapshot` |
| `POST` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/leave` | 없음 | 선택 `X-Request-Id` | `BundleSnapshot` 또는 `null` |
| `POST` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/photos/changes` | `BundlePhotoChangesRequest` | `Idempotency-Key` | `BundleSnapshot` |
| `POST` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/photos/{bundlePhotoId}/move` | `MoveBundlePhotoRequest` | 선택 `X-Request-Id` | `BundleSnapshot` |
| `PUT` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/photos/{bundlePhotoId}/caption` | `BundleTextRequest` | 선택 `X-Request-Id` | `BundleSnapshot` |
| `POST` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/publish` | `PublishBundleRequest` | `Idempotency-Key` | `BundleSnapshot` |
| `DELETE` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}` | 없음 | 선택 `X-Request-Id` | `null` |

`GET` 목록의 `status`는 필수이며 `DRAFT` 또는 `PUBLISHED`다. `page` 기본값은 `0`,
`size` 기본값은 `20`이고 `size`는 1~50이다. 초안은 `createdAt` 내림차순, 발행본은
`publishedAt` 내림차순이며 동률은 `id` 내림차순이다.

### 생성·조회·요약

생성 요청은 현재 번치의 멤버가 두 명 이상이고, Photo ID가 같은 번치의 `READY` 사진이며,
요청 목록이 1~20장이어야 한다. 중복 ID·누락·타 번치·`READY`가 아닌 사진이 하나라도
있으면 전체 요청을 거부한다. 초안은 번치별 최대 10개다. 생성 시 생성자와 현재 번치
멤버 중 해당 Photo 업로더를 참여자로 초기화한다.

```json
{
  "photoIds": [101, 102]
}
```

`POST` 생성, `GET` 목록, `GET /summary`, `GET /{bundleId}`의 `data` 형태는 아래
응답 DTO와 같다. 모든 조회는 현재 번치 멤버와 가입 완료 계정만 허용한다.

### 텍스트·날짜·공개 범위 저장

#### `BundleTextRequest`

```json
{
  "value": "제목 또는 코멘트 또는 캡션",
  "expectedVersion": 7,
  "mode": "DRAFT",
  "leaseToken": "2e7f9b3c-2d0a-4f11-8b2b-6b56dc4f8a33"
}
```

`leaseToken`은 제목·날짜·캡션의 `DRAFT` 저장에만 필요하다. 코멘트에는 보내지 않으며,
`PUBLISHED` 제목·날짜·캡션 수정에도 편집권이 필요하지 않다. `mode`는 모든 text
요청에 포함하며 실제 bundle 상태와 일치해야 한다.

- 제목은 NFC 정규화 후 trim하며 줄바꿈을 허용하지 않고 최대 40 grapheme이다.
- 코멘트는 NFC 정규화하며 최대 400 grapheme이다. `PUBLISHED`에서는 빈 코멘트를
  저장할 수 없다.
- 캡션은 NFC 정규화하며 최대 200 grapheme이다.
- 초안 제목은 빈 값일 수 있지만 발행 조건에서 `TITLE_REQUIRED`로 막힌다.

제목 lease target은 `TITLE`, 날짜 lease target은 `DATE`, 캡션 lease target은
`PHOTO:{bundlePhotoId}`다. 초안 편집권은 STOMP에서 획득하며 REST 저장에서는 socket ID를
받지 않고 현재 회원·session·target·`leaseToken`·미만료 상태·`expectedVersion`을 같은
트랜잭션에서 검사한다. REST 저장이 성공한 뒤 서버가 DB commit 후 해당 내용 이벤트를
STOMP로 한 번 발행한다. 클라이언트는 같은 내용을 STOMP 명령으로 다시 보내지 않는다.

#### `BundleVisibilityRequest`

```json
{
  "value": "PRIVATE",
  "expectedVersion": 3,
  "mode": "DRAFT"
}
```

`value`는 `PUBLIC` 또는 `PRIVATE`다. 공개 범위 저장에는 편집권이 필요하지 않지만
`expectedVersion`과 `mode`는 검사한다.

#### `BundleDateRequest`

```json
{
  "value": "2026-10-25",
  "expectedVersion": 5,
  "mode": "DRAFT",
  "leaseToken": "2e7f9b3c-2d0a-4f11-8b2b-6b56dc4f8a33"
}
```

`value`는 null일 수 있다. 서울 시간 기준 오늘보다 미래인 날짜는 거부한다. 날짜가
null이면 Photo 촬영일에서 최대 세 개의 `dateChips`를 계산하고, 날짜가 지정되면
`dateChips`는 빈 목록이다.

초안의 text·date·visibility·caption 저장은 참여자만 할 수 있다. 발행본의 제목·날짜·
공개 범위·사진·캡션 수정은 생성자 또는 번치 관리자만 할 수 있다. 발행본 코멘트는
해당 번들의 참여자 본인만 수정한다.

### 참여자·나가기·삭제

```json
{
  "memberId": 203
}
```

`POST /participants`의 body는 `BundleParticipantRequest`다. 초안 참여자가 현재 번치
멤버를 추가할 수 있다. `DELETE /participants/{memberId}`는 참여 중인 생성자 또는
번치 관리자가 호출하며 생성자를 제외한 참여자를 제거한다. 참여자 제거 시 그 회원의
번들 코멘트와 편집권도 제거한다.

`POST /leave`는 초안 참여자가 호출한다. 생성자가 나가면 초안을 삭제하고 `data: null`을
반환한다. 다른 참여자가 나가면 본인의 참여·코멘트·편집권을 제거한 `BundleSnapshot`을
반환한다. 발행본에서는 이 경로를 사용할 수 없다.

`DELETE /{bundleId}`는 생성자 또는 번치 관리자만 호출한다. 번들·번들 사진·참여자·
코멘트를 삭제하고 `data: null`을 반환한다. 사진함 원본 Photo는 삭제하지 않는다.

### 사진 구성·순서·캡션

#### `BundlePhotoChangesRequest`

```json
{
  "addPhotoIds": [103, 104],
  "removeBundlePhotoIds": [901],
  "mode": "DRAFT"
}
```

`addPhotoIds`와 `removeBundlePhotoIds`의 기본값은 빈 목록이다. 각 목록은 최대 20개며
ID는 양수이고 중복될 수 없다. 추가 Photo는 같은 번치의 `READY` 사진이어야 한다.
현재 존재하는 제거 ID가 다른 번들 소속이면 거부한다. 이미 사라진 제거 ID는
최신 구성에서 제외된 것으로 보고 다시 적용하지 않는다. 같은 원본 Photo를 한 요청에서 동시에 추가·제거하면 거부한다. 최종 사진 수는 1~20장이어야 한다.

사진 구성 변경은 `Idempotency-Key`를 사용한다. 같은 key와 같은 request body는 저장
결과를 재생하고, 같은 key에 다른 body를 보내면 `REQUEST_001`이다. 실제 추가·삭제가
없으면 `revision`과 이벤트를 만들지 않고 현재 snapshot을 반환한다.

#### `MoveBundlePhotoRequest`

```json
{
  "previousBundlePhotoId": 901,
  "nextBundlePhotoId": 903,
  "mode": "DRAFT"
}
```

`previousBundlePhotoId`와 `nextBundlePhotoId`는 새 위치의 양옆 anchor다. 둘 다 null인
요청은 사진이 하나뿐인 경우를 제외하면 유효한 위치가 아니며, anchor가 현재 목록에
없거나 서로 인접하지 않으면 `BUNDLE_006`이다. 순서 변경은 `PHOTOS` 이벤트를 만들고
변경된 사진 목록과 date chips를 보낸다.

초안의 사진 변경·이동은 참여자가 할 수 있다. 다른 회원이 해당 Photo lease를 보유한
동안에는 그 lease를 빼앗아 변경할 수 없다. 사진 구성·순서 변경도 REST 저장으로 처리하고,
DB commit 후 서버가 `PHOTOS` 이벤트를 발행한다.

#### 캡션

캡션 경로는 `BundleTextRequest`를 사용한다. 초안은 해당 `BundlePhoto`의
`PHOTO:{bundlePhotoId}` 편집권이 필요하며, REST에서 현재 회원·session·target·
`leaseToken`·만료·`expectedVersion`을 검증한다. 발행본은 생성자 또는 번치 관리자만
수정한다. 응답은 전체 `BundleSnapshot`이다.

### 발행

```json
{
  "expectedRevision": 12
}
```

발행은 생성자 또는 번치 관리자가 호출해야 하며, 초안에서는 호출자도 참여자여야 한다.
`expectedRevision`이 현재 `revision`과 다르면 `BUNDLE_003`이며 `detail`은 현재
번들 내용과 `version`을 포함한다.

발행 조건이 충족되지 않으면 `BUNDLE_005`와 다음 `detail.reasons`를 반환한다.

| reason | 조건 |
| --- | --- |
| `TITLE_REQUIRED` | 제목이 비어 있다. |
| `VISIBILITY_REQUIRED` | 공개 범위를 선택하지 않았다. |
| `PARTICIPANTS_REQUIRED` | 참여자가 두 명보다 적다. |
| `PARTICIPANT_INACTIVE` | 참여자 중 현재 번치 멤버가 아닌 회원이 있다. |
| `COMMENTS_REQUIRED` | 참여자 전원이 비어 있지 않은 코멘트를 작성하지 않았다. |
| `PHOTOS_REQUIRED` | 사진 수가 1~20장이 아니다. |

발행은 사진 원본이 여전히 같은 번치의 `READY` 상태인지 다시 잠그고, 상태를
`PUBLISHED`로 바꾸며 `publishedAt`을 기록하고 active lease를 해제한다. 발행 후
생성자와 번치 관리자만 공용 내용·사진을 수정할 수 있고, `mode: "DRAFT"` 저장은
허용하지 않는다.

발행 재시도 규칙은 다음과 같다.

1. 같은 `Idempotency-Key`와 같은 body는 최초 결과를 재생한다.
2. 같은 key에 다른 `expectedRevision`을 보내면 `REQUEST_001`이다.
3. 이미 발행된 번들에 다른 key로 재시도할 때는 `bundle_requests`에 저장된 최초
   `PUBLISH:{bundleId}` 요청의 fingerprint와 body fingerprint가 같아야 한다.
4. fingerprint가 같으면 다른 key도 같은 발행 결과를 반환하고 해당 key를 기록한다.
   fingerprint가 다르면 `BUNDLE_002`다.
5. 발행 뒤 제목·날짜·캡션을 수정해 `bundle.revision`이 변해도, 원래 발행 요청의
   fingerprint가 같으면 이 재시도는 허용된다. 재시도 판단에 `bundle.revision - 1`을
   사용하지 않는다.

### Idempotency-Key와 fingerprint

`Idempotency-Key`는 번치·회원·작업 종류별로 유일하다. 적용 작업은 `CREATE`,
`PHOTOS:{bundleId}`, `PUBLISH:{bundleId}`다. key와 body fingerprint가 같은 요청은
저장된 bundle 결과를 재생한다. 같은 key에 다른 body를 보내면 다음 오류를 반환한다.

```text
HTTP 409
error.code = REQUEST_001
error.message = error.idempotency_conflict
```

삭제된 bundle의 idempotency row도 남아 있으므로, 삭제 뒤 같은 key를 재사용하면
삭제된 bundle을 복원하지 않고 `RESOURCE_001`을 반환한다.

## REST 응답 DTO

### `BundleSnapshot`

| field | type | 의미 |
| --- | --- | --- |
| `id` | `Long` | 번들 ID |
| `bunchId` | `Long` | 번치 ID |
| `createdBy` | `Long` | 생성자 member ID |
| `status` | `DRAFT \| PUBLISHED` | 현재 상태 |
| `title` | `String` | 제목 |
| `titleVersion` | `Long` | 제목 버전 |
| `visibility` | `PUBLIC \| PRIVATE \| null` | 공개 범위 |
| `visibilityVersion` | `Long` | 공개 범위 버전 |
| `date` | `LocalDate \| null` | 선택 날짜 |
| `dateVersion` | `Long` | 날짜 버전 |
| `revision` | `Long` | 저장된 내용 revision |
| `photos` | `BundlePhotoResponse[]` | position 오름차순 사진 |
| `participants` | `BundleParticipantResponse[]` | joinedAt 오름차순 참여자 |
| `comments` | `BundleCommentResponse[]` | member별 코멘트 |
| `dateChips` | `LocalDate[]` | 날짜가 지정되지 않았을 때의 촬영일 추천 |
| `publishBlockers` | `String[]` | 초안의 현재 발행 차단 사유, 발행본은 빈 목록 |
| `permissions` | `BundlePermissions` | 현재 요청자 권한 |
| `leases` | `LeaseState[]` | 현재 활성 편집권의 공개 상태 |
| `createdAt` | `Instant` | 생성 시각 |
| `updatedAt` | `Instant` | 마지막 내용 변경 시각 |
| `publishedAt` | `Instant \| null` | 발행 시각 |

`dateChips`는 지정 날짜가 null인 경우에만 같은 번들 사진의 현지 촬영일을 그대로
모아 빈도 내림차순·날짜 오름차순으로 최대 세 개 반환한다. 미래 촬영일은 제외한다.

### 하위 응답 DTO

```json
{
  "id": 901,
  "photoId": 101,
  "imageAssetId": 7001,
  "imageUrl": "https://images.example/7001.jpg",
  "position": 0,
  "addedBy": 201,
  "addedAt": "2026-10-25T01:02:03Z",
  "caption": "",
  "captionVersion": 0,
  "captionUpdatedBy": null,
  "captionUpdatedAt": null
}
```

`BundlePhotoResponse`의 `photoId`는 원본 Photo가 삭제된 발행본에서 null이 될 수 있다.
이때도 `imageAssetId`, `imageUrl`, `position`, `caption`과 캡션 버전은 유지한다.

```json
{
  "memberId": 201,
  "joinedAt": "2026-10-25T01:02:03Z"
}
```

`BundleParticipantResponse`는 참여자 member ID와 참여 시각을 가진다.

```json
{
  "memberId": 201,
  "content": "함께 고른 사진이에요.",
  "version": 1,
  "createdAt": "2026-10-25T01:02:03Z",
  "updatedAt": "2026-10-25T01:03:03Z"
}
```

`BundleCommentResponse`는 번들마다 회원 한 명당 하나다. 초기 참여자처럼 row가 아직
없는 회원은 `comments` 배열에서 생략되며, 그 경우에만 빈 content와 version 0의
암묵 상태를 적용한다. 참여자 추가 응답에는 빈 comment row가 포함되므로 응답의
version을 사용해야 한다.

```json
{
  "administrator": false,
  "creator": true,
  "participant": true,
  "canEdit": true,
  "canPublish": true,
  "canDelete": true
}
```

`BundlePermissions`의 `canEdit`은 초안 참여자 또는 발행본 생성자·관리자에게,
`canPublish`는 초안 참여자이면서 생성자·관리자일 때, `canDelete`는 생성자·관리자에게
true다.

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "total": 0
}
```

`BundlePage`는 `items`, `page`, `size`, `total`을 반환한다.

```json
{
  "draftCount": 2,
  "publishedCount": 5,
  "stage": 2
}
```

`BundleSummary.stage`는 published count로 계산한다. 0~3은 1, 4~7은 2, 8~11은
3, 12~15는 4, 16 이상은 5다.

### 오류와 conflict detail

| HTTP | code | 발생 조건 | `detail` |
| --- | --- | --- | --- |
| 400 | `COMMON_001` | 잘못된 UUID·필수 header·범위·문자열 또는 누락/타 번치/비READY Photo 선택 | validation이면 `field`, `reason`을 포함할 수 있다. |
| 400 | `COMMON_002` | JSON 역직렬화 실패 | 보통 null |
| 401 | `AUTH_001` | access JWT·활성 session 없음 | null |
| 403 | `AUTH_002` | 번치 소속·작업 권한 없음 | null |
| 404 | `RESOURCE_001` | 없는 번들·번들 사진 또는 경로 번치와 다른 번들 | null |
| 409 | `REQUEST_001` | 같은 idempotency key의 fingerprint 불일치 | null |
| 409 | `BUNDLE_001` | 번치 인원 부족·초안 10개 초과 | null |
| 409 | `BUNDLE_002` | `mode`와 실제 상태 불일치 또는 발행 retry fingerprint 불일치 | 보통 `{"status":"PUBLISHED"}` 또는 null |
| 409 | `BUNDLE_003` | expected version/revision 불일치 | 현재 값과 `version` |
| 409 | `BUNDLE_004` | 편집권 없음·만료·소유자 불일치·member/session/connection/token 불일치 | 공개 `LeaseState` 또는 null |
| 409 | `BUNDLE_005` | 발행 조건 미충족 | `{"reasons":[...]}` |
| 409 | `BUNDLE_006` | 사진 최종 수·현재 anchor·사진 구성 충돌 | 현재 bundle 내용 또는 `{photos,dateChips}` |

버전 오류의 실제 detail 예시는 다음과 같다.

```json
{
  "value": "현재 제목",
  "version": 3
}
```

캡션 오류에는 `bundlePhotoId`도 포함된다.

```json
{
  "value": "현재 캡션",
  "bundlePhotoId": 901,
  "version": 2
}
```

편집권 오류의 공개 상태는 token·session·connectionId를 노출하지 않는다.

```json
{
  "target": "TITLE",
  "memberId": 201,
  "leaseVersion": 4,
  "expiresAt": "2026-10-25T01:03:15Z"
}
```

`BUNDLE_005`는 다음과 같이 현재 모든 blocker를 배열로 반환한다.

```json
{
  "reasons": ["TITLE_REQUIRED", "COMMENTS_REQUIRED"]
}
```

## WebSocket/STOMP

### 연결·구독·명령

endpoint는 `/ws`이며 `CONNECT` native header의 Bearer access JWT로 인증한다.
허용 Origin은 `BUNCH_WS_ALLOWED_ORIGINS`로 제한하고 URL query는 거부한다.
연결 후에도 세션·번치 소속·작업 권한을 다시 검사한다.

| 구독 destination | 수신 대상 |
| --- | --- |
| `/topic/bunches/{bunchId}/bundles/{bundleId}` | 현재 번치 멤버의 내용 변경·편집권 공개 상태·활동 |
| `/user/queue/bundle-leases` | 요청 연결의 편집권 획득·heartbeat·해제·명령 오류 |
| `/user/queue/bundle-activity` | 요청 연결의 활동/조회 heartbeat 응답 |

private 응답은 같은 회원의 다른 연결로도 전달하지 않는다. 공개 편집권 상태에는
`memberId`, `target`, `leaseVersion`, `expiresAt`만 포함하고 token·session·connectionId는
제외한다.
`DELETED`는 번들이 삭제된 뒤에도 해당 번치의 현재 멤버에게 전달한다.

client `SEND` destination은 다음과 같다. 명령 body는 공통 응답 envelope로 감싸지 않는다.

```text
/app/bunches/{bunchId}/bundles/{bundleId}/lease/acquire
/app/bunches/{bunchId}/bundles/{bundleId}/lease/heartbeat
/app/bunches/{bunchId}/bundles/{bundleId}/lease/release
/app/bunches/{bunchId}/bundles/{bundleId}/activity
```

편집권 명령과 `COMMENT` 활동은 초안 참여자만 사용할 수 있다. `VIEW` 활동은 초안·
발행본의 현재 번치 멤버가 사용할 수 있다.

### 기본 연결·구독·REST 저장 흐름

공동 편집 화면의 기본 흐름은 다음 순서를 따른다.

1. `/ws`에 `CONNECT`하고 native `Authorization` header로 access JWT를 보낸다. 서버는
   인증된 `memberId`·`sessionId`와 서버 connectionId를 연결 문맥에 둔다.
2. 번들 topic과 필요한 private queue를 `SUBSCRIBE`한 뒤, REST `GET`으로 현재
   `BundleSnapshot`과 revision·공개 lease 상태를 조회한다.
3. 내용 변경은 REST endpoint로만 보낸다. 모든 요청에는 최신 `expectedVersion`을 넣는다.
   `DRAFT` 제목·날짜·캡션 저장은 단독 사용자라도 저장 전에 해당 target lease를
   acquire하고, 그 응답의 현재 `leaseToken`도 넣는다. visibility·comment처럼 lease가
   필요하지 않은 저장은 해당 DTO의 버전·상태 검증만 적용한다.
4. 서버가 권한·member/session·target과 필요한 lease·버전을 같은 저장 경계에서 확인하고 DB
   commit을 완료한 뒤, 저장된 실제 변경값을 담은 STOMP 내용 이벤트를 한 번 발행한다.
   클라이언트는 같은 내용을 STOMP `SEND`로 보내거나 서버 이벤트를 다시 반사하지 않는다.

연결·구독과 REST 저장은 snapshot·이벤트 수신과 내용 변경을 위한 기본 절차다. `DRAFT`
제목·날짜·캡션 저장에는 단독 사용자도 `TITLE`·`DATE`·`PHOTO:{bundlePhotoId}` 중 해당
target의 lease 획득·heartbeat·release가 필요하며, 재연결 복구도 아래 절차를 따른다.

### 서버 공통 메시지

서버 JSON 메시지는 topic·private queue·STOMP `ERROR` frame 모두 다음 여섯 필드를 사용한다.
Kotlin 타입은 `BundleRealtimeMessage<T>`이고 `type`은 `BundleEventType` enum의 이름이다.
프론트는 payload의 필드 존재 여부 대신 최상위 `type`으로 분기한다.

```json
{
  "type": "TITLE",
  "bunchId": 42,
  "bundleId": 7,
  "revision": 13,
  "requestId": "8a4f3b2e-c9e1-4d42-9db5-7abf8a2d0e31",
  "data": {
    "value": "새 제목",
    "version": 2
  }
}
```

| 필드 | 계약 |
| --- | --- |
| `type` | 아래 `BundleEventType` 값. JSON 대문자 문자열 |
| `bunchId`, `bundleId` | 대상 식별자. scope를 확정할 수 없는 프로토콜 오류에서만 null 가능 |
| `revision` | 내용 변경은 적용 후 revision, 편집권/활동 응답은 당시 내용 revision. 확인할 수 없는 오류는 null |
| `requestId` | 내용 변경의 요청 UUID. 편집권·활동·오류는 null |
| `data` | 이벤트별 실제 값. `DELETED`는 명시적 null |

최상위 `changes`, `states`, `token`, 활동용 `memberId`를 사용하던 메시지는 모두
`data` 아래로 옮긴다. 이전 형식과 혼용하지 않는다. REST 응답·명령 body·구독 경로는 유지한다.

### EventType 목록과 data

내용 변경 이벤트만 새로운 revision을 적용한다. 모두 DB 커밋 후 전송한다.

| `type` | `data` |
| --- | --- |
| `CREATED` | status, title/titleVersion, visibility/visibilityVersion, date/dateVersion, publishedAt, photos, dateChips, participants, comments |
| `TITLE` | `{value, version}` |
| `VISIBILITY` | `{value, version}` |
| `DATE` | `{value, version, dateChips}` |
| `COMMENT` | `BundleCommentResponse` |
| `CAPTION` | `BundlePhotoResponse` |
| `PARTICIPANTS` | `{participants, comments, removedCommentMemberIds?}` |
| `PHOTOS` | `{photos, dateChips}` |
| `PUBLISHED` | `CREATED`와 같은 전체 내용. status=PUBLISHED |
| `DELETED` | null |

다음 이벤트는 내용 revision을 증가시키지 않는다.

| `type` | `data` | 전달 경로 |
| --- | --- | --- |
| `LEASES` | `{states: LeaseState[]}` | 번들 topic |
| `LEASE_GRANTED` | `{target, token, leaseVersion, expiresAt}` | 요청 연결 lease queue |
| `LEASE_HEARTBEAT` | `{target, token, leaseVersion, expiresAt}` | 요청 연결 lease queue |
| `LEASE_RELEASED` | `{target}` | 요청 연결 lease queue |
| `ACTIVITY` | `{memberId, activity: COMMENT \| VIEW}` | 번들 topic과 요청 연결 activity queue |
| `COMMAND_REJECTED` | `{code}` | 요청 연결 lease queue 또는 프로토콜 ERROR |

코멘트 저장은 최상위 `type: "COMMENT"`이고, 작성 중 표시는
`type: "ACTIVITY"`와 `data.activity: "COMMENT"`다. `data.activity`는 별도의
`BundleActivityType` enum으로 정의하며 `COMMENT`, `VIEW`만 사용한다.

### DRAFT 저장 편집권 절차: 획득·heartbeat·해제

이 lease 절차는 `DRAFT` 제목·날짜·캡션 REST 저장에 필수인 전 단계다. 단독 사용자도
예외 없이 저장할 target을 먼저 acquire하고, 받은 현재 `leaseToken`을 저장 요청에 넣어야
한다. 편집 중에는 5초마다 heartbeat를 보내고, 저장을 마치거나 화면을 이탈할 때 release한다.
`visibility`·`comment` 저장은 lease 없이 DTO의 버전·상태만 검증한다.

획득 명령 body에는 target만 포함한다.

```json
{
  "target": "TITLE"
}
```

`target`은 `TITLE`, `DATE`, `PHOTO:{bundlePhotoId}`다. 서버는 STOMP 연결의
`connectionId`와 인증 principal의 `memberId`·`sessionId`를 사용한다. lease가 없거나
만료·release된 경우 획득할 수 있다. 활성 lease를 재획득할 수 있는 경우는 같은
`memberId`·`sessionId`·`connectionId`일 때뿐이며, 이때도 매번 새 token과 새
만료 시각을 발급하고 이전 token을 무효화한다. 같은 member/session의 다른
connectionId는 `BUNDLE_004` conflict다. 유효 lease의 `release`, 연결 disconnect 또는
15초 만료가 확인된 뒤에만 다른 연결이 획득할 수 있다. acquire가 DB 잠금에서 기다리는
동안 연결이 종료되면, 획득 커밋 직후 서버 연결 상태를 다시 확인해 해당 연결의 lease를
정리하고 grant를 거부한다.

응답은 `LEASE_GRANTED`이며 요청한 연결에서만 다음 값을 받는다. `token`은 이 private
queue 밖으로 보내지 않는다.

```json
{
  "type": "LEASE_GRANTED",
  "bunchId": 42,
  "bundleId": 7,
  "revision": 12,
  "requestId": null,
  "data": {
    "target": "TITLE",
    "token": "2e7f9b3c-2d0a-4f11-8b2b-6b56dc4f8a33",
    "leaseVersion": 1,
    "expiresAt": "2026-10-25T01:03:15Z"
  }
}
```

다른 연결에는 같은 시점의 `LEASES`가 전달된다.

```json
{
  "type": "LEASES",
  "bunchId": 42,
  "bundleId": 7,
  "revision": 12,
  "requestId": null,
  "data": {
    "states": [
      {"target": "TITLE", "memberId": 201, "leaseVersion": 1, "expiresAt": "2026-10-25T01:03:15Z"}
    ]
  }
}
```

heartbeat와 release에는 target과 획득한 token을 추가한다. 서버는 요청 연결의
`memberId`·`sessionId`·`connectionId`, target, 현재 token, 미만료 상태를 모두 검증한다.
REST 저장은 socket ID를 보내지 않으므로 같은 lease 검증에서 connectionId를 요구하지
않고 member/session·target·token·만료·expectedVersion을 검증한다.

```json
{
  "target": "TITLE",
  "token": "2e7f9b3c-2d0a-4f11-8b2b-6b56dc4f8a33"
}
```

5초마다 heartbeat를 보내고 마지막 유효 갱신부터 15초 동안 편집권을 유지한다.
heartbeat 응답은 `LEASE_HEARTBEAT`, release 응답은 `LEASE_RELEASED`다.
현재 내용 revision도 공통 envelope에서 확인한다. 연결 종료 처리는 저장된
`connectionId`와 실제 종료된 연결이 일치할 때만 그 연결의 lease를 해제한다. 이전
connection의 늦은 disconnect나 이전 token은 새 lease에 영향을 주지 않는다. 재연결 시에는
새 connectionId에서 새 token으로 획득한다. 앱은 60초 무입력 시 dirty 입력을 REST로
저장한 뒤 release하며, 저장 실패 시 미저장 입력을 유지한다.

### 활동·조회 heartbeat

명령 body에는 `type`만 보낸다. trim 후 대문자로 해석하며 생략하면 COMMENT다.

```json
{
  "type": "VIEW"
}
```

응답과 topic 활동 이벤트는 모두 `ACTIVITY` envelope다.

```json
{
  "type": "ACTIVITY",
  "bunchId": 42,
  "bundleId": 7,
  "revision": 12,
  "requestId": null,
  "data": {"memberId": 201, "activity": "VIEW"}
}
```

편집권이 없는 화면도 5초마다 VIEW를 보내 마지막 이벤트 누락을 확인한다.
활동은 내용 저장과 구분하고 content revision을 증가시키지 않는다.

### 오류

매핑된 명령의 편집권·저장 충돌은 요청 연결 lease queue로 보낸다.

```json
{
  "type": "COMMAND_REJECTED",
  "bunchId": 42,
  "bundleId": 7,
  "revision": null,
  "requestId": null,
  "data": {"code": "BUNDLE_004"}
}
```

프로토콜 오류도 같은 envelope를 `ERROR` frame body로 사용하되 scope와 revision은 null이다.
인증·구독 권한을 통과하지 못한 frame은 응답 없이 폐기할 수 있다. 클라이언트는 timeout도
실패로 처리한다. 오류에 token·인증 header·session·REST conflict detail을 노출하지 않는다.

## 추가 복구 절차: 동기화·복구 규칙

최초 진입·구독 직후·재연결·내용 revision 누락 시 REST snapshot으로 복구한다.

```text
GET /api/v1/bunches/{bunchId}/bundles/{bundleId}
```

- 내용 이벤트의 revision이 로컬 값 이하이면 중복/이전 이벤트로 무시한다.
- 다음 revision이면 `type`에 맞춰 `data`의 실제 값을 적용한다.
- revision이 건너뛰면 snapshot으로 복구하고, heartbeat에서 더 최신 revision을 확인했을 때도 복구한다.
- 재연결한 connection은 이전 connection의 leaseToken을 재사용하지 않는다. snapshot을
  확인한 뒤 필요한 target을 새로 acquire하고, 새 token과 최신 expectedVersion으로만
  보류 REST 저장을 재시도한다.
- `LEASES`, `LEASE_GRANTED`, `LEASE_HEARTBEAT`, `LEASE_RELEASED`, `ACTIVITY`, `COMMAND_REJECTED`에는 내용 중복 제거 규칙을 적용하지 않는다. 같은 revision에서도 새 편집권/활동 정보가 올 수 있다.
- topic과 private queue 양쪽에서 받은 동일 활동은 회원별 활동 표시를 갱신한다.
- 다른 회원의 이벤트나 snapshot을 적용할 때 본인의 미저장 입력을 유지한다.
- 발행·삭제·참여 제외로 거부된 저장은 무한 재시도하지 않고 현재 상태로 화면을 전환한다.

사진함 HTTP API 없이 Photo fixture만으로 실제 업로드·목록·중복 병합 연동까지
완료한 것으로 판단하지 않는다. iOS/WebView의 입력·무입력 저장·화면 복귀는 별도 수동 검증 대상이다.

## 관련 계약

- [번들 기능 계약](bundle.md)
- [사진함 기반 영속성 계약](photo-foundation.md)
- [로그인·온보딩 API 계약](login-onboarding-api.md)


## 구현 참고

CONNECT 인증은 [Spring STOMP 토큰 인증](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html)의
메시지 사용자 설정 방식을 따른다. 연결 안의 송수신 순서는
[Spring 메시지 순서 설정](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/ordered-messages.html)을 사용한다.
