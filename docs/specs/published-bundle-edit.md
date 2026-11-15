# 발행 번들 상세·내용 저장·공개 범위

이슈 #88. 2026-11-07 승인한 원문 6.2.1~5·6.2.11~13·6.3.2가 기존 발행 후
개별 저장·코멘트 편집 계약보다 우선한다. 이슈 #124는 2026-11-10 재전달된
[피드 시트](https://docs.google.com/spreadsheets/d/1ScziF7WoYLHRiC_UYJBkKsi0jL6KKQJD2TNDzW1QGfU/edit?gid=294136194#gid=294136194)의
E19:K20(6.2.11·6.2.12), E23:K23(6.2.15)에 따라 내용 저장과 공개 범위 설정을 분리한다.
초안 공동 편집·최초 발행 조건은 [번들 계약](bundle.md)을 따른다.

## 전체 저장

`PUT /api/v1/bunches/{bunchId}/bundles/{bundleId}/published`는 현재 ACTIVE OWNER만 호출한다.
가입 완료·번치 소속·경로 소속·PUBLISHED 상태·공통 피드 접근·역할을 서버에서 재검사한다.
선택 `X-Request-Id` UUID는 커밋 후 이벤트의 상관 ID다.

```json
{
  "expectedRevision": 12,
  "title": "함께한 하루",
  "date": "2026-07-09",
  "photos": [
    {"bundlePhotoId": 201},
    {"photoId": 302}
  ],
  "locations": [
    {"provider": "kakao", "externalId": "123", "name": "해변", "address": "제주시", "position": 0}
  ]
}
```

사진·제목·위치·날짜의 최종 값을 보낸다. 날짜 null·장소 빈 배열을 허용한다. 사진은 배열
순서대로 1~20장, 장소는 [장소 계약](bundle-locations.md)에 따라 최대 3개다. 제목은
NFC·trim 후 1~40 grapheme, 날짜는 서울 기준 미래 날짜를 거부한다.
공개 범위는 아래 전용 경로로만 변경한다.
사진마다 `bundlePhotoId`(보존할 번들 사진)와 `photoId`(새 사진함 사진) 중 정확히 하나를
보낸다. 기존 사진 ID는 해당 번들 소속이어야 하며 중복 ID·중복 원본 사진을 거부한다.
원본 Photo가 삭제된 기존 번들 사진도 ID·이미지 참조·추가자·시각·픽을 유지한다.
새 사진은 같은 번치의 READY 사진이어야 한다. 기존 사진함 업로드 한도를 추가로 소비하지 않는다.
참여 멤버와 멤버별 코멘트·픽은 입력 대상이 아니며 발행 후 변경할 수 없다.
`EditPublishedBundleRequest.visibility`는 제거한다. 구 클라이언트가 일괄 저장 JSON에
`visibility`를 남겨도 기존 Jackson 미지 필드 처리대로 무시하며 공개 범위와 그 버전은
변경하지 않는다. 클라이언트는 공개 범위 변경을 전용 경로로 전환해야 한다.
캡션은 현재 발행 입력·응답 계약에서 제거한다. 이미 적용된 DB/Flyway의 caption 컬럼·값과
마이그레이션 이력은 내부 historical data로만 보존하며 API 입력·응답·이벤트로 노출하거나
수정하지 않는다.

서버는 번치→번들을 잠근 뒤 expectedRevision과 전체 입력을 검증한다. 불일치는
409 `BUNDLE_003`, detail `{revision: 현재값, version: 현재값}`이다. 잘못된 입력은 400 `COMMON_001`,
초안 요청은 409 `BUNDLE_002`, 역할 부족은 403 `AUTH_002`, 경로 불일치는 404다.
동일 revision으로 경합한 서로 다른 수정은 하나만 성공한다.
번치 락을 기다리기 전에 상위 트랜잭션이 회원·소속·번치·번들을 읽었어도 잠금 획득 후
대상 엔티티를 다시 읽어 현재 ACTIVE·OWNER·revision을 검사한다. 영속성 컨텍스트 전체를
clear하지 않고 보류 변경을 flush한 다음 대상만 refresh하여 다른 상위 변경을 보존한다. 성공 응답은 BundleSnapshot이다.
성공했으나 응답을 잃은 요청은 최신 상세를 재조회한다. stale revision은 no-op도 거부한다.

저장 전 내용은 앱 로컬 상태다. 서버에 임시 수정본을 저장하는 API는 없다. 전체 저장은 한
트랜잭션으로 반영하며 독자는 커밋 전의 전체 내용 또는 커밋 후의 전체 내용을 읽는다.
실제 변경에만 revision·updatedAt을 한 번 갱신하고 `EDITED` 전체 내용 이벤트를 커밋 후 보낸다.
변경된 필드 버전만 증가한다. 동일한 정규화 결과는 revision·시각·이벤트를 변경하지 않는다.
발행 시각·참여자 순서·코멘트·좋아요·소셜 댓글과 피드 순서를 보존하고 수정 알림은 보내지 않는다.
보존한 `bundlePhotoId`는 기존 사진 row와 그 픽을 유지하며, 배열에서 빠진 사진은 사진 row와
그 사진의 픽을 함께 cascade한다. 새 `photoId`로 만든 사진에는 픽을 만들지 않는다.
픽 row가 없는 사진의 version도 참여자의 고정 초기값을 사용한다. 내용·공개 범위 변경이나
원본 Photo 삭제로 이 version을 바꾸지 않으며, 미참여 조회자의 초기 version은 0이다.
장소 교체는 MANDATORY `BundleLocationService.replaceLocked`를 호출한다. 동기 출처 제거
이벤트의 실제 핀 정리는 #92 소유이며 별도 임시 writer를 만들지 않는다. listener 실패도 전체 롤백한다.

기존 제목·날짜·사진 추가/삭제/이동 경로는 PUBLISHED에서 OWNER도
409 `BUNDLE_002`로 거부한다. 참여자 추가/삭제·leave·comments/me도 발행 후 거부한다.
이 경로들의 DRAFT version·참여 권한은 유지한다. 구형 편집권 계약은 2026-11-14 결정으로 제거한다.

## 공개 범위 단독 저장 (#124)

기존 `PUT /api/v1/bunches/{bunchId}/bundles/{bundleId}/visibility`를 확장한다.

```json
{"value": "PRIVATE", "expectedVersion": 3, "mode": "PUBLISHED"}
```

`value`는 PUBLIC 또는 PRIVATE, `expectedVersion`은 최신 `visibilityVersion`이다.
제목·사진·위치·날짜·참여자·코멘트·픽을 재전송하지 않는다. 현재 상태·권한·버전을 검증한다.
`mode: DRAFT`의 기존 초안 참여자 권한은 유지하고, `PUBLISHED`는 현재 ACTIVE OWNER만 허용한다.
발행 참여자였는지는 요구하지 않는다. 가입 완료·소속·경로·공통 피드 접근을 기존
`BundleAccessService.lock`으로 재검사하고 번치→번들 잠금 획득 후 최신 상태·역할·버전을 확인한다.
현재 번치에 따른 공통 접근 규칙은 그대로 적용하며 별도 접근 우회나 새 선택 규칙을 만들지 않는다.

- 모드와 실제 상태 불일치: 409 `BUNDLE_002`와 현재 status.
- 버전 불일치: no-op도 409 `BUNDLE_003`, detail `{value: 현재 공개 범위, version: 현재 버전}`.
- ACTIVE OWNER가 아님: 403 `AUTH_002`. 없는·삭제된·경로가 다른 번들은 기존 404를 유지한다.
- 공통 접근에서 거부되는 숨김·차단·신고·탈퇴·가입 중 계정은 기존 오류를 유지한다.
- value/mode가 없거나 잘못된 enum, expectedVersion 누락/null은 400 `COMMON_002`다.

실제 변경에만 visibilityVersion·revision·updatedAt을 한 번 갱신한다. 동일 값과 최신 버전은
no-op으로 시각·버전·이벤트를 유지한다. 성공 응답은 기존 BundleSnapshot이다.
커밋 후 기존 `VISIBILITY` 이벤트 `{value, version}`를 한 번 보내며 선택 `X-Request-Id`를 유지한다.
롤백 시 이벤트를 보내지 않는다. 발행본 변경에서는 활동 기록·발행 준비 알림·수정 알림을 만들지 않는다.
발행 시각·제목·사진 ID/순서·위치·날짜·참여자 코멘트·좋아요·소셜 댓글은 보존한다.

내용 저장은 visibilityVersion을 바꾸지 않는다. 먼저 내용 저장이 끝나면 기존 visibilityVersion으로
범위 변경을 이어갈 수 있다. 반대로 범위 변경은 aggregate revision을 올리므로 그 이전
expectedRevision의 내용 저장은 충돌로 거부하고 재조회한다. 두 경로는 같은 잠금 순서를 사용해
서로의 필드를 덮어쓰지 않는다. 기존 전체 revision 경계를 완화하거나 별도 revision을 추가하지 않는다.

PRIVATE(멤버 공개)로 바꾸면 외부 피드·번치 프로필 목록·상세에서 제외하되 내부 멤버 접근은 유지한다.
다시 PUBLIC으로 바꾸면 저장된 반응과 기존 발행 시각을 유지한다. 공개 번치의 PUBLIC은 전체 공개,
비공개 번치의 PUBLIC은 승인된 팔로워 번치에게만 공개이며 PENDING은 허용하지 않는다.

픽은 #123 소유다. 범위 저장은 사진/참여자 변경이나 픽 저장·삭제 경로를 호출하지 않는다.
공개 범위 저장은 기존 사진 ID의 픽 row·version을 보존한다. 내용 수정에서 제거한 사진의 픽은
#123의 lifecycle/FK 경계로 정리하며, 보존한 사진의 픽은 유지하고 새 사진에는 픽을 만들지 않는다.
신규 의존성·마이그레이션은 추가하지 않는다.

검증 기준: OWNER/CO_OWNER/일반 멤버·미참여 OWNER·동시 역할 변경·비활성 소속,
모드/버전/경로/삭제/운영 숨김·no-op·커밋/롤백 이벤트·내용 저장과의 경합·반응과 시각 보존,
실제 피드/프로필/상세 비노출 및 공개 복구·비공개 번치 승인 팔로우 경계를 자동 테스트한다.
실제 iOS/WebView와 Figma GUI3 화면은 별도 확인 대상이며 시트 기반 서버 검증으로 대체했다고 주장하지 않는다.

## 상세·권한·집계

내부 GET과 외부 `published-bundles` GET 모두 FeedAccessService의 최신 접근 판정을 적용한다.
외부 상세는 사진·사진별 `picks` 순서, `locations`, `revision`, `visibility`, `permissions`를
제공한다. 사진 픽과 기존 발행 데이터의 보존 규칙은 캡션 제거와 무관하게 유지한다.
내부·외부 상세는 참여자를 전원 제공하며 발행 당시 joinedAt/id 순서를 유지한다.
참여자 코멘트 `comments`도 같은 참여자 순서다. 기존 필드 이름은 유지한다.

참여자의 현재 계정과 번치 ACTIVE 소속이 모두 유효하면 닉네임·프로필 이미지와 코멘트·픽 표시를
보여준다. 나가기·내보내짐·탈퇴는 `nickname: "작성자"`, `imageUrl: null`,
`profileAvailable: false`, `content: "삭제된 코멘트입니다"`로 응답하고 원문과 픽 row는 보존한다.
같은 계정의 번치 재참여 시 기존 순서·코멘트를 복구한다. `profileAvailable: true`일 때
`GET /api/v1/members/{memberId}/social-profile`(#94)로 이동한다. WITHDRAWN 계정이나 새
계정은 같은 member ID가 아니므로 복구하지 않는다.

`canEdit/canDelete`는 현재 OWNER만 true다. `canReport`는 현재 번치가 있고 OWNER 관리
대상이 아닐 때 true, `canLike/canComment`는 현재 번치가 있을 때 true다. 화면 진입 시점의
힌트이며 모든 쓰기에서 다시 검사한다. 소속 0개는 공개 읽기만 허용한다.
`likeCount/likedByCurrentBunch/commentCount`는 별도 소셜 서비스의 접근 필터가 적용된 값이다.
`comments`는 기존 참여자 코멘트이고 소셜 댓글 목록은 #90 별도 API를 사용한다.
삭제된 소셜 댓글 자리도 commentCount에 포함한다.

## 삭제·검증

OWNER 삭제 시 번들 하위 사진 연결·사진 픽·참여자 코멘트·반응·소셜 댓글은 FK CASCADE로 정리한다.
사진함 원본은 보존한다. #92의 핀은 source FK SET NULL과 장소 스냅샷으로 보존한다.
Stage는 삭제 후 발행본 수로 계산한다. 삭제 알림은 없다.
실제 PostgreSQL·Redis로 동시 저장, 커밋 전/후 조회, 롤백, 권한·접근 재검사, 원본 보존,
표시 복구, 반응 보존·삭제와 기존 초안 회귀를 검증한다. 실제 iOS/WebView/APNs는 별도 검증이다.


## 피드 참여자 배치 조립

`BundleAttributionService.publishedAll(Collection<Bundle>)`은 접근 검사를 마친 발행본을
최대 500개 받아 `Map<Long, BundleAttribution>`을 반환한다. 빈 입력은 빈 map이고
잘못된 ID·DRAFT·501개 이상은 COMMON_001이다. 단건 published(bundle)도 이 메서드로 위임한다.
참여자·코멘트·활성 소속+회원+프로필 이미지의 세 번 쿼리로 전체를 조립하며 번들별 또는
이미지별 추가 조회를 하지 않는다. B의 최대50개 피드 카드에서도 참여자 전원을 제공한다.
이 내부 조립 API 자체는 사용자 접근 검사를 하지 않으므로 호출자가 A 검사를 선행한다.

#92 실제 서비스가 포함된 통합 테스트에서는 원본 장소 제거 시 자기/외부 번치의 해당 출처 핀만
삭제되고, 다른 번들 출처의 동일 장소 핀은 유지됨을 확인한다. 이후 사진 INSERT 실패 시 실제
핀 삭제까지 전체 롤백하고, 번들 삭제 시 남은 핀 ID·내용·시각은 source NULL로 보존한다.
