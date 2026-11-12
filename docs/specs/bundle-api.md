# 번들 REST·STOMP 클라이언트 계약

2026-11-10 #107의 [제목 CRDT](bundle-title-crdt.md)·[typing/presence](bundle-realtime-107.md)·
[장소 의도 작업](bundle-locations.md)을 초안 계약의 최신 기준으로 따른다.
아래 lease 절차는 구 클라이언트·캡션 호환용이며 제목/날짜/위치/사진 작업의 필수조건이 아니다.

2026-11-07 승인한 [발행본 계약](published-bundle-edit.md)과 2026-11-10 재전달된
6.2.12·6.2.15(#124)를 따른다. 발행본 내용은 `PUT /{bundleId}/published`, 공개 범위는
기존 `PUT /{bundleId}/visibility`로 분리한다. 나머지 개별 내용 저장 API는 DRAFT 전용이다.
사진 픽도 DRAFT에서만 변경할 수 있고, 발행본에서는 상세 조회로만 반환한다.
발행 후 참여자·멤버별 코멘트는 변경할 수 없다. 기존 comments 필드는 참여자 코멘트로 유지한다.

캡션은 현재 활성 API 계약에서 제거한다. 캡션 쓰기·편집 경로, 발행 입력, 번들·공개·피드
응답 필드, `CAPTION` 이벤트와 캡션 검증은 제공하지 않는다. 이미 적용된 DB/Flyway의
caption 컬럼·값과 마이그레이션 이력은 내부 과거 데이터로만 보존하며 새 DDL이나 호환
계층은 추가하지 않는다.

장소 목록의 REST·LOCATION lease·이벤트·snapshot·내부 일괄 편집/콕 연계는
[장소 목록 계약](bundle-locations.md)을 따른다. `BundleSnapshot`에 `locations`와
`locationVersion`이 추가되며 기존 발행 조건에 장소 필수 조건은 추가하지 않는다.

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
| `X-Request-Id` | UUID | `title`, `visibility`, `date`, `comment`, 참여자 변경, `leave`, 사진 이동·제거, 삭제 | 생략하면 서버가 UUID를 생성한다. 변경 이벤트의 `requestId`로 사용한다. |
| `Idempotency-Key` | UUID | 생성·사진 구성 변경·발행 | 필수다. 같은 작업의 재시도와 중복 발행을 구분한다. |

모든 요청의 `BundleActor`는 인증된 `memberId`와 `sessionId`로만 구성한다. STOMP lease에
보관하는 `connectionId`는 서버가 연결에서 얻는 값이며 client body·header로 받지 않는다.
REST에는 socket ID를 받지 않는다. 제목·날짜·사진·장소는 독점 lease가 필요 없다.
private token과 connectionId는 REST 응답·공개 상태에 포함하지 않는다.

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
| `locationVersion` | 장소 목록 전체 |
| `BundlePhotoPick.version` | 한 사진·회원 픽 row의 마지막 변경 당시 번들 revision. row가 없으면 해당 참여자의 고정 `pickInitialVersion`, 미참여 조회자는 0 |
| `BundleCommentResponse.version` | 한 회원의 코멘트 |

CRDT 제목과 사진/위치 작업은 최신 상태에 병합하고, 날짜는 마지막 저장을 적용한다.
최종 발행의 expectedRevision은 호환용으로만 받으며 stale revision을 거부 사유로 쓰지 않는다.
그 밖에 버전 검증을 사용하는 경로는 서버 값과 다르면 변경하지 않고 현재 값과
현재 버전을 오류 `detail`로 반환한다. 같은 값을 다시 보내는 no-op 저장은 해당 필드
버전과 `revision`을 증가시키지 않는다. 클라이언트는 응답에 없는 버전을 추측하지
않는다.

사진 픽은 예외적으로 원하는 상태를 보내는 멱등 요청이다. 현재 `picked`와 요청 값이
같으면 `expectedVersion`이 오래되어도 현재 `BundlePhotoPicks`를 성공 응답으로 반환하며
revision·`updatedAt`·활동을 바꾸지 않는다. 상태가 달라지고 expected version이 현재
픽 row 버전(픽 row가 없으면 해당 참여자의 고정 `pickInitialVersion`)과 다르면 `BUNDLE_003`을
반환하고 `error.detail`에 현재 `BundlePhotoPicks`를 담는다.

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
| `PUT` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/photos/{photoId}/pick` | `BundlePhotoPickRequest` | 없음 | `BundlePhotoPicks` |
| `POST` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}/publish` | `PublishBundleRequest` | `Idempotency-Key` | `BundleSnapshot` |
| `DELETE` | `/api/v1/bunches/{bunchId}/bundles/{bundleId}` | 없음 | 선택 `X-Request-Id` | `null` |

`GET` 목록의 `status`는 필수이며 `DRAFT` 또는 `PUBLISHED`다. `page` 기본값은 `0`,
`size` 기본값은 `20`이고 `size`는 1~50이다. 초안은 `updatedAt` 내림차순, 발행본은
`publishedAt` 내림차순이며 동률은 `id` 내림차순이다.

### 생성·조회·요약

2026-11-10 #122: 업로더 자동 참여를 생성자 + `participantMemberIds`로 대체한다.
생성자는 입력하지 않으며 다른 멤버를 한 명 이상 명시한다. 양수·중복 없는 member ID만 허용한다.
생략/빈 목록/생성자 포함은 `INVALID_INPUT`(400)이다. 사진·멤버 목록의 null 또는 null 원소는
`INVALID_JSON`(400)으로 거부한다. 선택 순서대로 생성자 뒤에 참여자를 저장한다.
현재 ACTIVE 소속과 ACTIVE 계정·가입 완료를 번치 잠금 이후 재검증하며, 타 번치·없는·이탈·비활성·
가입 미완료 멤버는 `FORBIDDEN`(403)으로 전체 거부한다. 이탈자를 자동 제외하는 K24 예외는 제안/미확정이다.
프론트는 멤버 목록을 다시 불러와 선택을 갱신한 뒤 재요청한다.
선택한 비업로더도 참여하고 고르지 않은 업로더는 참여하지 않는다. 코멘트 필수 대상·참여 알림도 결과 참여자 기준이다.
생성자에게 자기 참여 알림은 보내지 않는다.

```json
{
  "photoIds": [101, 102],
  "participantMemberIds": [22, 23],
  "recommendationId": null
}
```

`recommendationId`는 기존 추천 연결 입력이다. 사진함·추천 내부 정책은 변경하지 않는다.

2026-11-10 사용자 보완으로 생성 경로 입력을 두지 않고 서버 삭제 사진 처리를 통일한다.
3.3.4(K14)의 남은 사진 자동 생성과 4.1.4(K24)의 재선택 차이는 프론트가 처리한다.
서버는 일부/전체 사진이 누락됐으면 전체 생성을 중단하고 `PHOTO_SELECTION_CHANGED`
(409, `PHOTO_004`)와 `detail: {"missingPhotoIds": [102]}`를 반환한다.
프론트는 누락 ID를 빼고 순서를 갱신한 뒤 해당 화면 계약에 맞춰 재요청한다.
존재하는 타 번치·`READY` 아닌 사진은 누락으로 취급하지 않으며 `INVALID_INPUT`(400)이다.
사진 입력은 양수·중복 없는 1~20장이어야 한다. 번치 활성 멤버 최소 2명과 초안 최대 10개는 그대로 적용한다.
생성 실패는 번들·사진·참여자·추천 소비·알림·멱등 기록을 함께 롤백한다.
실패 요청은 키를 소비하지 않으므로 누락 사진을 뺀 정정 요청에 같은 키를 사용할 수 있다.

같은 키의 동일 요청은 검증·알림을 반복하지 않고 기존 초안의 현재 snapshot을 반환한다.
원래 사진 ID와 순서·선택 멤버 ID와 순서·추천 ID 중 하나라도 바뀌면
`IDEMPOTENCY_CONFLICT`다. 이미 삭제된 생성 결과는 기존 `RESOURCE_NOT_FOUND`를 유지한다.
이전 업로더 자동 참여 body로의 생성은 더 이상 지원하지 않는다. 앱은 선택 멤버를 반드시 보내고
기존 키를 다른 body에 재사용하지 않아야 한다. 스키마 변경은 없다.

`POST` 생성, `GET` 목록, `GET /summary`, `GET /{bundleId}`의 `data` 형태는 아래
응답 DTO와 같다. 모든 조회는 현재 번치 멤버와 가입 완료 계정만 허용한다.

### 텍스트·날짜·공개 범위 저장

#### `BundleTextRequest`

```json
{
  "value": "제목 또는 코멘트",
  "expectedVersion": 7,
  "mode": "DRAFT",
  "leaseToken": "2e7f9b3c-2d0a-4f11-8b2b-6b56dc4f8a33"
}
```

`leaseToken`은 현재 제목·날짜·코멘트 저장에 필요 없으며,
`PUBLISHED`는 이 경로로 저장할 수 없다. `mode`는 모든 text
요청에 포함하며 실제 bundle 상태와 일치해야 한다.

- 제목은 NFC 정규화 후 trim하며 줄바꿈을 허용하지 않고 최대 40 grapheme이다.
- 코멘트는 NFC 정규화하며 최대 400 grapheme이다. 발행 후에는 수정할 수 없다.
- 초안 제목은 빈 값일 수 있지만 발행 조건에서 `TITLE_REQUIRED`로 막힌다.

새 공동 편집은 [제목 CRDT](bundle-title-crdt.md), [장소 작업](bundle-locations.md),
[typing/presence](bundle-realtime-107.md)를 쓴다. 기존 문자열 제목 저장은 expectedVersion을
검사한 후 CRDT 문서도 갱신한다. 제목·날짜·위치·사진은 leaseToken을 무시한다.
REST 커밋 후 STOMP 변경 이벤트를 한 번 전파한다.

#### `BundleVisibilityRequest`

```json
{
  "value": "PRIVATE",
  "expectedVersion": 3,
  "mode": "DRAFT"
}
```

`value`는 `PUBLIC` 또는 `PRIVATE`다. 편집권 없이 최신 `visibilityVersion`을
`expectedVersion`으로 보내며 `mode`는 실제 상태와 일치해야 한다. DRAFT는 참여자,
PUBLISHED는 ACTIVE OWNER만 허용한다. 발행 참여자 여부는 요구하지 않는다.
필수 expectedVersion의 누락/null 또는 잘못된 value/mode JSON은 400 `COMMON_002`다.
모드 충돌은 `BUNDLE_002`, 버전 충돌은 no-op도 `BUNDLE_003`이며 기존 접근·권한 검사를 유지한다.

실변경에 visibilityVersion·revision·updatedAt만 갱신하고 커밋 후 기존 `VISIBILITY` 이벤트를 보낸다.
최신 버전의 동일 값은 no-op이다. 발행본의 내용·발행 시각·반응·코멘트는 보존하고 알림을 보내지 않는다.
범위 변경 후 과거 expectedRevision의 내용 저장은 충돌하며, 내용 저장 후에는 변경되지 않은
visibilityVersion으로 범위를 저장할 수 있다. 상세 오류·공개 의미·픽 연계는 [발행본 계약](published-bundle-edit.md)을 따른다.

#### `BundleDateRequest`

```json
{
  "value": "2026-10-25",
  "expectedVersion": 5,
  "mode": "DRAFT",
  "leaseToken": "2e7f9b3c-2d0a-4f11-8b2b-6b56dc4f8a33"
}
```

날짜 expectedVersion·leaseToken은 호환용으로만 받으며 마지막 서버 저장값을 적용한다.
`value`는 null일 수 있다. 서울 시간 기준 오늘보다 미래인 날짜는 거부한다. 날짜가
null이면 Photo 촬영일에서 최대 세 개의 `dateChips`를 계산하고, 날짜가 지정되면
`dateChips`는 빈 목록이다.

초안의 text·date·visibility 저장은 참여자만 할 수 있다. 발행본의 제목·날짜·공개 범위·사진
수정은 현재 메인 관리자 OWNER만 할 수 있다. 발행본 코멘트는
발행 후 누구도 수정하지 않는다. 발행본 내용은 전체 일괄 저장 경로, 공개 범위는 전용 경로를 사용한다.
일괄 저장 DTO에서는 visibility를 제거하며 구 JSON의 해당 미지 필드는 무시되어 범위를 바꾸지 않는다.
기존 사진의 픽은 동결한다. 최초 발행은 #107 계약을 따른다.

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

`DELETE /{bundleId}`는 DRAFT에서 생성자 또는 해당 초안에 참여 중인 OWNER/CO_OWNER만,
PUBLISHED에서 현재 OWNER만 호출한다. 번들·번들 사진·참여자·
코멘트를 삭제하고 `data: null`을 반환한다. 사진함 원본 Photo는 삭제하지 않는다.

### 사진 구성·순서·픽

#### `BundlePhotoChangesRequest`

`capacityMode` 기본값은 `ATOMIC_APPLY`다. 사진함 선택 화면의 add/remove 차이를
최신 목록에 병합하고 초과하면 전체 거부한다. 일반·갤러리 추가는 `PARTIAL_ADD`를
명시한다. removeBundlePhotoIds는 이 모드에서 빈 목록이어야 한다. 남은 정원까지
선택 순으로 수락하고 나머지 원본 사진은 사진함에 남긴다. 업로드 결과 자체와는 별개다.
모든 제출 원본의 번치/READY를 먼저 검사하므로 잘못된 ID를 정원 초과로 숨기지 않는다.

이 요청의 snapshot에는 `photoChangeResult: {addedPhotoIds, rejectedPhotoIds, replayed}`를
포함한다. 추가·거부 ID는 photos.id(원본)이며 재시도는 최신 snapshot과 replayed=true,
빈 결과 배열을 반환한다. 재시도는 사진을 복원하거나 토스트를 다시 표시하지 않는다.
결과 정보는 요청자 응답에만 있고 공용 PHOTOS 이벤트에는 없다.

사진함에서 기존 사진의 선택을 풀었다 다시 고르면 번호가 뒤로 붙는다. 이때
`reselectedPhotoIds`에 해당 **원본 Photo ID**를 넣고, `addPhotoIds`에는 이번에 새로
고른 사진과 재선택한 사진을 **고른 순서대로 함께** 넣는다. 예를 들어 기존 `[A,B,C]`에서
새 D, 기존 A, 새 E 순으로 선택했다면 addPhotoIds는 `[D,A,E]`, reselectedPhotoIds는
`[A]`이며 결과는 `[B,C,D,A,E]`다. 재선택한 기존 BundlePhoto 행·캡션은 유지한다.
다른 멤버가 추가/이동한 사진 중 본인이 재선택하지 않은 항목의 최신 상대 순서는 유지한다.
이미 삭제된 재선택 사진은 추가 대상으로 바꾸지 않으며 복원하지 않는다.

`reselectedPhotoIds` 기본값은 빈 목록이다. 최대 20개·양수·중복 없음이며 모두 addPhotoIds에
포함되어야 한다. `ATOMIC_APPLY`만 허용한다. reselectedPhotoIds가 비어 있으면 기존과
같이 이미 들어 있는 addPhotoIds는 중복 추가 no-op이다. 재선택·추가·삭제와 정원 검사는
한 트랜잭션이며 순서만 바뀌어도 PHOTOS/revision을 한 번 갱신한다. 같은 키 재시도는
뒤에 온 이동을 취소하지 않는다.


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

기본 ATOMIC_APPLY의 fingerprint는 기존 addPhotoIds/removeBundlePhotoIds/mode 형태를 유지한다.
PARTIAL_ADD만 capacityMode를 포함해 다른 작업으로 구분한다. reselectedPhotoIds는 비어 있지
않을 때만 fingerprint에 포함하므로 기존 두 모드의 저장된 요청 키와 호환된다.
사진 구성 변경은 `Idempotency-Key`를 사용한다. 같은 key와 같은 request body는 저장
결과를 재생하고, 같은 key에 다른 body를 보내면 `REQUEST_001`이다. 실제 추가·삭제가
없으면 `revision`과 이벤트를 만들지 않고 현재 snapshot을 반환한다.

#### `MoveBundlePhotoRequest`

[사진 이동 계약](bundle-photo-order.md)을 따른다. 비인접 기준을 최신 목록에 재적용하고
삭제 기준의 과거 이웃으로 위치를 보정한다. 같은 사진은 나중 서버 처리 이동이 최종이다.
같은 X-Request-Id 재시도는 오래된 이동을 다시 실행하지 않는다.


```json
{
  "previousBundlePhotoId": 901,
  "nextBundlePhotoId": 903,
  "mode": "DRAFT"
}
```

`previousBundlePhotoId`와 `nextBundlePhotoId`는 새 위치의 양옆 anchor다. 둘 다 null인
요청은 사진이 하나뿐인 경우를 제외하면 유효한 위치가 아니다. 불명확한 외부/미확인 anchor는
`BUNDLE_006`이며 비인접·이전에 삭제된 기준은 최신 위치로 보정한다. 순서 변경은 `PHOTOS` 이벤트를 만들고
변경된 사진 목록과 date chips를 보낸다.

초안의 사진 변경·이동은 참여자가 할 수 있다. 다른 회원의 legacy Photo lease는 이를 막지 않는다.
사진 구성·순서 변경도 REST 저장으로 처리하고,
DB commit 후 서버가 `PHOTOS` 이벤트를 발행한다.

#### `BundlePhotoPickRequest`

```json
{
  "picked": true,
  "expectedVersion": 12
}
```

`picked`와 `expectedVersion`은 모두 필수이며 null은 허용하지 않는다. `expectedVersion`은
0 이상이어야 한다. 경로의 `photoId`는 사진함 Photo ID가 아니라 현재 번들의
`BundlePhoto.id`다.

`PUT /photos/{photoId}/pick`은 현재 번치 멤버가 번들 snapshot에서 읽은 사진별 픽을
변경하는 DRAFT 전용 경로다. 초안 참여자만 호출할 수 있고, 참여하지 않은 현재 번치
멤버는 `GET` 상세·목록 snapshot에서 픽을 읽을 수만 있다. PUBLISHED에서 호출하면
`BUNDLE_002`, 다른 번들의 사진 ID·없는 사진이면 `RESOURCE_001`을 반환한다.

성공 응답은 다음 `BundlePhotoPicks`다.

```json
{
  "count": 2,
  "pickedByMe": true,
  "version": 13,
  "members": [
    {
      "memberId": 201,
      "joinedAt": "2026-10-25T01:02:03Z",
      "nickname": "지민",
      "imageUrl": "https://images.example/member-201.jpg",
      "profileAvailable": true
    }
  ]
}
```

`count`는 `picked=true`인 참여자 수, `pickedByMe`는 현재 요청자의 상태다. `members`는
참여자의 `joinedAt ASC, id ASC` 순서를 따르며 픽하지 않은 참여자는 포함하지 않는다.
활성 계정·번치 소속의 프로필만 표시하고, 그 외에는 `nickname: "작성자"`, `imageUrl: null`,
`profileAvailable: false`를 사용한다. 같은 계정이 번치에 재참여하면 복구하지만
WITHDRAWN 계정이나 새 계정은 복구하지 않는다.

서버는 번치와 번들을 잠근 뒤 현재 픽 row를 다시 읽어 부모 트랜잭션의 stale entity를
사용하지 않는다. 실제 변경이면 번들 revision·`updatedAt`·현재 소속의 활동을 한 번 갱신하고,
픽 row의 `version`을 그 revision으로 저장한다. row가 없으면 해당 참여자의 고정
`pickInitialVersion`을 사용한다. 생성 시 초기 참여자와 마이그레이션 전 참여자는 0이며,
이후 참여자 추가 시 그 변경으로 증가할 `bundle.revision`을 초기값으로 저장한다.
다른 사진·멤버의 픽이나 무관한 초안 편집은 이 초기값을 바꾸지 않는다. 참여자를 제거하면
해당 픽 row가 지워지고, 다시 추가하면 새 초기 버전을 발급하여 이전 참여의 늦은 변경 요청을
거부한다. 초기값은 부모 트랜잭션에 남은 참여자 엔티티 대신 DB의 scalar 조회로 읽는다.
`picked=false` row도 ABA 방지를 위해 남긴다. 사진·참여자 FK는 같은 번들 범위로 묶고
`ON DELETE CASCADE`를 적용한다. 참여자가 아닌 조회자는 초기 version 0을 받으며 쓰기는 거부된다.
발행 후 내용·공개 범위 변경이나 원본 사진 삭제로 빈 픽의 version도 바뀌지 않는다.

`V39__bundle_pick_initial_versions.sql`은 참여자에 non-null·0 이상인 초기 버전
컬럼을 추가한다. 기존 픽 row·버전은 변경하지 않는다. 전환 전 row 없는 픽의 버전을
캐시한 클라이언트는 한 번 상세를 새로 읽어 고정 초기값으로 전환한다.

픽 변경은 STOMP 내용 이벤트나 알림을 만들지 않는다. 따라서 topic revision이 건너뛸 수
있으며, 클라이언트는 픽 화면 재진입·재연결 또는 revision gap에서 상세 snapshot을 다시
읽는다. 픽 응답의 `version`을 다음 요청에 사용하고, 사진·본인별 요청을 직렬화하며,
빠른 입력은 마지막 희망 상태로 합친다. `409` 또는 응답 유실이면 snapshot을 다시 읽은
뒤 최신 희망 상태만 재시도하고, 오프라인 요청을 무한 replay하지 않는다.

### 발행

```json
{"visibility": "PUBLIC", "expectedRevision": 12}
```

visibility는 최종 선택 값이다. expectedRevision은 선택 호환 필드로 stale여도 거부하지 않는다.
구 클라이언트가 visibility를 생략하면 저장된 초안 범위를 사용하며 둘 다 없으면 BUNDLE_005다.
새 클라이언트는 범위 화면에서 뒤로 갈 때 저장하지 않고 최종 요청에만 범위를 전달한다.
발행 준비 snapshot.publishBlockers는 VISIBILITY_REQUIRED를 포함하지 않는다.

번치→번들 잠금 아래 현재 참여·생성자/관리자 권한, 사진 원본 READY, 아래 조건을 재검사한다.
선택 범위·최신 내용·참여자·publishedAt·publishedBy를 한 번에 저장하고 PUBLISHED로 전파한다.
실패하면 범위 선택도 롤백한다. 같은 시점의 다른 편집은 이 잠금 순서 전후로 직렬 처리되며
발행 뒤 도착한 DRAFT 저장은 BUNDLE_002다.

| reason | 조건 |
| --- | --- |
| TITLE_REQUIRED | 빈 제목 |
| TITLE_TOO_LONG | CRDT 병합 제목이 40 grapheme 초과 |
| VISIBILITY_REQUIRED | 최종 범위도 기존 범위도 없음 (최종 발행 전용) |
| PARTICIPANTS_REQUIRED | 참여자 2명 미만 |
| PARTICIPANT_INACTIVE | 비활성 참여자 |
| COMMENTS_REQUIRED | 코멘트 미작성 참여자 |
| PHOTOS_REQUIRED | 사진 1~20장 조건 실패 |

실제 발행자의 `publishedBy`와 `publisherNickname`은 snapshot·PUBLISHED에 포함한다.
이탈자는 기존 익명화 규칙으로 이름을 표시하며 과거 알 수 없는 발행자는 null이다.
같은 Idempotency-Key와 같은 body는 기존 결과를 반환한다. 같은 key의 다른 body는 REQUEST_001,
다른 key는 최초 body fingerprint와 같을 때만 같은 발행을 반환한다.
visibility를 생략한 legacy 요청은 expectedRevision-only fingerprint를 보존한다. 재시도는 내용을 재발행하지 않는다.
발행 후 OWNER 수정·삭제 계약은 기존 발행본 문서를 따른다.

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
| `locationVersion` | `Long` | 장소 목록 버전 |
| `locations` | `BundleLocationResponse[]` | position 오름차순 최대 3개 장소 |
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
| `likeCount` | `Long` | 공통 접근 필터를 적용한 소셜 좋아요 수 |
| `likedByCurrentBunch` | `Boolean` | 현재 번치 공동 좋아요 상태 |
| `commentCount` | `Long` | 삭제 자리를 포함한 소셜 댓글·답글 수. comments 배열 크기와 다름 |

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
  "addedAt": "2026-10-25T01:02:03Z"
}
```

`BundlePhotoResponse`의 `photoId`는 원본 Photo가 삭제된 발행본에서 null이 될 수 있다.
이때도 `imageAssetId`, `imageUrl`, `position`, `picks`는 유지한다.

```json
{
  "count": 2,
  "pickedByMe": false,
  "version": 13,
  "members": []
}
```

`BundlePhotoPicks`는 내부 `BundleSnapshot.photos[i].picks`와 외부 발행 번들 상세의
`photos[i].picks`에 같은 shape으로 포함된다. 외부 상세에서 읽을 수 있는 픽은 변경할 수
없으며, 기존 사진의 픽은 순서 변경·원본 Photo 삭제와 무관하게 유지된다.

```json
{
  "memberId": 201,
  "joinedAt": "2026-10-25T01:02:03Z"
}
```

`BundleParticipantResponse`는 참여자 member ID와 참여 시각,
`nickname/imageUrl/profileAvailable`을 가진다. 초안은 현재 프로필과 참여자별 `role`
(`OWNER | CO_OWNER | MEMBER`) 및 `creator`(초안 생성자 여부)를 별도로 내려준다.
관리자와 생성자가 겹쳐도 두 정보는 독립적이며 앱이 배지 우선순위를 결정한다.
`PARTICIPANTS` 이벤트도 같은 정보를 제공한다. `PERMISSIONS` 수신 후 상세 재조회하면
현재 역할을 반영한다. 발행본의 role은 null, creator는 false이며 역할 배지 계약 대상이 아니다.
나간/탈퇴 멤버는 작성자·기본 이미지이며
profileAvailable은 false다. 참조 ID는 기존 값으로 유지하고 원문 코멘트는 응답에서 숨긴다.

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
version을 사용해야 한다. `id`는 코멘트 row ID다. 발행본 comments는 참여자 순서이며
nickname/imageUrl/profileAvailable을 제공한다. 비활성 작성자는 content를 「삭제된 코멘트입니다」로
표시한다. 초안에서 추가한 표시 필드의 기본값은 null/false다.

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

`BundlePermissions.administrator`는 OWNER와 CO_OWNER, `creator`는 생성자,
`participant`는 참여 여부를 각각 나타낸다. `canEdit`은 초안 참여자 또는 발행본 OWNER에게,
`canPublish`는 초안 참여자이면서 생성자·관리자일 때 true다. `canDelete`는 초안에서
참여 중인 생성자·OWNER/CO_OWNER, 발행본에서 OWNER에게만 true다. 미참여 관리자는
초안 편집·발행·폐기 권한이 없다. `canEdit`은 공용 내용 권한이다. 발행 후 코멘트는 누구도 변경할 수 없다.
발행본의 관리 메뉴는 현재 선택 번치의 OWNER에게만 제공한다. 추가된
`canReport/canLike/canComment`와 소속 0개 읽기 전용 정책은 [발행본 계약](published-bundle-edit.md)을 따른다.

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

`BundleSummary.stage`는 PUBLIC/PRIVATE 전체 published count로 계산한다. 0~1은 1,
2~5는 2, 6~9는 3, 10 이상은 4다. 삭제 후에도 현재 수로 다시 계산한다.
`bunch.bundle.stage-thresholds`의 기본값 `0,2,6,10`을 사용하며 환경 변수
`BUNCH_BUNDLE_STAGE_THRESHOLDS`로 지정한다. 내부·외부 계산은 같은 정책을 재사용한다.

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
| 409 | `BUNDLE_002` | `mode`와 실제 상태 불일치, 발행 retry fingerprint 불일치 또는 PUBLISHED 픽 변경 | 보통 `{"status":"PUBLISHED"}` 또는 null |
| 409 | `BUNDLE_003` | expected version/revision 불일치 | 일반 저장은 현재 값과 `version`, 픽은 현재 `BundlePhotoPicks` |
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

픽 상태가 달라지는 stale 요청의 conflict detail은 다음과 같다.

```json
{
  "count": 1,
  "pickedByMe": false,
  "version": 14,
  "members": [
    {"memberId": 201, "joinedAt": "2026-10-25T01:02:03Z", "profileAvailable": true}
  ]
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

편집권 명령은 초안 참여자만 사용할 수 있다. `COMMENT` 활동은 초안 참여자에게만 허용한다. 발행본 코멘트 변경은 금지한다. `VIEW` 활동은 초안·발행본의 현재 번치 멤버가 사용할 수 있다.
모든 명령에서 현재 소속·참여 상태를 재검사하며 역할만으로 편집권을 우회할 수 없다.
활동 표시·VIEW·lease 획득·heartbeat는 `lastActivityAt`과 `updatedAt`을 바꾸지 않는다.

### 기본 연결·구독·REST 저장 흐름

공동 편집 화면의 기본 흐름은 다음 순서를 따른다.

1. `/ws`에 `CONNECT`하고 native `Authorization` header로 access JWT를 보낸다. 서버는
   인증된 `memberId`·`sessionId`와 서버 connectionId를 연결 문맥에 둔다.
2. 번들 topic과 필요한 private queue를 `SUBSCRIBE`한 뒤, REST `GET`으로 현재
   `BundleSnapshot`과 revision·공개 lease 상태를 조회한다.
3. 내용 변경은 REST로 보낸다. 제목 CRDT는 라이브러리 상태, 사진·장소는 ID 의도를
   보내고 날짜는 저장할 값을 보낸다. 전체 문자열·전체 장소 목록 호환 경로와 코멘트는
   해당 expectedVersion을 사용한다.
4. 서버가 현재 권한·상태·정원을 DB 잠금 아래 확인하고 커밋 후 변경 이벤트를 보낸다.
5. 실제 입력의 TYPE·입장·이탈과 날짜 선택/장소 편집/사진 작업의 BEGIN/END는
   비독점 typing/presence로 전송한다. 내용 저장과 별도이며 수정 가능한 필드를 잠그지 않는다.

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
| `CREATED` | status, title/titleVersion, visibility/visibilityVersion, date/dateVersion, locations/locationVersion, publishedAt, photos, dateChips, participants, comments |
| `TITLE` | `{value, version, title, titleVersion, titleCrdtState, titleTooLong}` |
| `VISIBILITY` | `{value, version}` |
| `DATE` | `{value, version, dateChips}` |
| `LOCATION` | `{locations: BundleLocationResponse[], version: locationVersion}` |
| `COMMENT` | `BundleCommentResponse` |
| `PARTICIPANTS` | `{participants, comments, removedCommentMemberIds?}` |
| `PHOTOS` | `{photos, dateChips}` |
| `PUBLISHED` | `CREATED`와 같은 전체 내용. status=PUBLISHED |
| `EDITED` | 발행본 전체 일괄 저장. `CREATED`와 같은 전체 내용 |
| `DELETED` | null |

다음 이벤트는 내용 revision을 증가시키지 않는다.

| `type` | `data` | 전달 경로 |
| --- | --- | --- |
| `PERMISSIONS` | `null`. snapshot/현재 역할 재조회 | 번들 topic |
| `DATE_CHIPS` | `{dateChips}`. 날짜 미지정 초안만 | 번들 topic |
| `LEASES` | `{states: LeaseState[]}`. legacy 호환 | 번들 topic |
| `LEASE_GRANTED` | `{target, token, leaseVersion, expiresAt}` | 요청 연결 lease queue |
| `LEASE_HEARTBEAT` | `{target, token, leaseVersion, expiresAt}` | 요청 연결 lease queue |
| `LEASE_RELEASED` | `{target}` | 요청 연결 lease queue |
| `ACTIVITY` | `{memberId, activity: COMMENT \| VIEW}` | 번들 topic과 요청 연결 activity queue |
| `COMMAND_REJECTED` | `{code}` | 요청 연결 lease queue 또는 프로토콜 ERROR |

코멘트 저장은 최상위 `type: "COMMENT"`이고, 작성 중 표시는
`type: "ACTIVITY"`와 `data.activity: "COMMENT"`다. `data.activity`는 별도의
`BundleActivityType` enum으로 정의하며 `COMMENT`, `VIEW`만 사용한다.

### Legacy 캡션/구 클라이언트 편집권 절차: 획득·heartbeat·해제

이 lease 절차는 기존 연결의 전환·정리용이다. 현재 제목·날짜·위치·사진·코멘트 저장은
이를 요구하지 않으며, 서버가 매 요청의 권한·상태와 해당 내용 버전을 검증한다.

획득 명령 body에는 target만 포함한다.

```json
{
  "target": "TITLE"
}
```

`target`은 `TITLE`, `DATE`, `LOCATION`, `PHOTO:{bundlePhotoId}`다. `PHOTO:{bundlePhotoId}`는
사진 이동·제거 보호용이며, 서버는 STOMP 연결의
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

현재 서버는 허용된 명령 경로에서도 편집권 명령의 초안 참여 권한과 `COMMENT` 활동의
본인 코멘트 권한을 dispatch 전에 검사한다. 이 검사를 통과하지 못하면 `COMMAND_REJECTED`나
`ERROR`를 보내지 않고 frame을 폐기하며 연결은 유지한다. 비참여 번치 멤버의 `VIEW` 활동은
계속 허용한다. 실행 지점의 권한 검증도 유지하며, 사전 검사를 제거해 거부 응답을 바꾸지 않는다.

내용 변경 이벤트는 트랜잭션 commit 이후에만 전송한다. commit 전에는 구독자에게 전달하지
않고, rollback된 변경은 전송하지 않는다.

## 추가 복구 절차: 동기화·복구 규칙

최초 진입·구독 직후·재연결·내용 revision 누락 시 REST snapshot으로 복구한다.

```text
GET /api/v1/bunches/{bunchId}/bundles/{bundleId}
```

- 내용 이벤트의 revision이 로컬 값 이하이면 중복/이전 이벤트로 무시한다.
- 다음 revision이면 `type`에 맞춰 `data`의 실제 값을 적용한다.
- revision이 건너뛰면 snapshot으로 복구하고, heartbeat에서 더 최신 revision을 확인했을 때도 복구한다.
- 재연결한 connection은 서버 최신 snapshot과 CRDT 상태로 교체한다. 서버 미반영 입력은
  버리고 재전송하지 않는다. 이전 연결의 leaseToken이나 오프라인 입력 큐를 재사용하지 않는다.
- `PERMISSIONS`, `DATE_CHIPS`, `PRESENCE`, `TYPING`, `LEASES`, `LEASE_GRANTED`, `LEASE_HEARTBEAT`, `LEASE_RELEASED`, `ACTIVITY`, `COMMAND_REJECTED`에는 내용 중복 제거 규칙을 적용하지 않는다. 같은 revision에서도 새 편집권/활동 정보가 올 수 있다.
- 픽 요청은 사진·본인별로 하나씩 직렬화한다. 성공 응답의 `version`을 다음 요청에
  사용하고, 빠른 탭은 마지막 desired state만 남긴다. `BUNDLE_003` 또는 timeout처럼
  응답을 잃은 경우 snapshot을 재조회한 뒤 마지막 desired state만 한 번 재시도한다.
- 픽 변경에는 전용 STOMP 이벤트·알림이 없으므로 revision gap을 오류로 단정하지 않는다.
  픽 화면 재진입·재연결 시 snapshot을 읽어 `photos[i].picks`를 갱신한다.
- topic과 private queue 양쪽에서 받은 동일 활동은 회원별 활동 표시를 갱신한다.
- 연결 중 원격 제목 변경은 CRDT로 현재 입력과 합친다. 드래그 중 원격 순서는 드롭 후 반영한다.
  연결 복구 snapshot에는 서버 미반영 입력을 다시 합치지 않는다.
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
