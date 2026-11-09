# 발행 번들 상세·일괄 저장

이슈 #88. 2026-11-07 승인한 원문 6.2.1~5·6.2.11~13·6.3.2가 기존 발행 후
개별 저장·코멘트 편집 계약보다 우선한다. 초안의 lease·발행 조건은 유지한다.

## 전체 저장

`PUT /api/v1/bunches/{bunchId}/bundles/{bundleId}/published`는 현재 ACTIVE OWNER만 호출한다.
가입 완료·번치 소속·경로 소속·PUBLISHED 상태·공통 피드 접근·역할을 서버에서 재검사한다.
선택 `X-Request-Id` UUID는 커밋 후 이벤트의 상관 ID다.

```json
{
  "expectedRevision": 12,
  "title": "함께한 하루",
  "visibility": "PUBLIC",
  "date": "2026-07-09",
  "photos": [
    {"bundlePhotoId": 201, "caption": "남겨 둔 사진"},
    {"photoId": 302, "caption": "사진함에서 추가"}
  ],
  "locations": [
    {"provider": "kakao", "externalId": "123", "name": "해변", "address": "제주시", "position": 0}
  ]
}
```

모든 최종 값을 보낸다. 날짜 null·장소 빈 배열·빈 캡션은 허용한다. 사진은 배열 순서대로
1~20장, 장소는 [장소 계약](bundle-locations.md)에 따라 최대 3개다. 제목은 NFC·trim 후
1~40 grapheme, 캡션은 NFC 후 최대 200 grapheme, 날짜는 서울 기준 미래 날짜를 거부한다.
사진마다 `bundlePhotoId`(보존할 번들 사진)와 `photoId`(새 사진함 사진) 중 정확히 하나를
보낸다. 기존 사진 ID는 해당 번들 소속이어야 하며 중복 ID·중복 원본 사진을 거부한다.
원본 Photo가 삭제된 기존 번들 사진도 ID·이미지 참조·추가자·시각을 유지한다.
새 사진은 같은 번치의 READY 사진이어야 한다. 기존 사진함 업로드 한도를 추가로 소비하지 않는다.
참여 멤버와 멤버별 코멘트는 입력 대상이 아니며 발행 후 변경할 수 없다.

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
장소 교체는 MANDATORY `BundleLocationService.replaceLocked`를 호출한다. 동기 출처 제거
이벤트의 실제 핀 정리는 #92 소유이며 별도 임시 writer를 만들지 않는다. listener 실패도 전체 롤백한다.

기존 제목·공개 범위·날짜·사진 추가/삭제/이동·캡션 경로는 PUBLISHED에서 OWNER도
409 `BUNDLE_002`로 거부한다. 참여자 추가/삭제·leave·comments/me도 발행 후 거부한다.
이 경로들의 DRAFT lease·version·참여 권한은 유지한다.

## 상세·권한·집계

내부 GET과 외부 `published-bundles` GET 모두 FeedAccessService의 최신 접근 판정을 적용한다.
외부 상세는 사진·캡션 순서, `locations`, `revision`, `visibility`, `permissions`를 제공한다.
내부·외부 상세는 참여자를 전원 제공하며 발행 당시 joinedAt/id 순서를 유지한다.
참여자 코멘트 `comments`도 같은 참여자 순서다. 기존 필드 이름은 유지한다.

참여자의 현재 계정과 번치 ACTIVE 소속이 모두 유효하면 닉네임·프로필 이미지와 코멘트를
보여준다. 나가기·내보내짐·탈퇴는 `nickname: "작성자"`, `imageUrl: null`,
`profileAvailable: false`, `content: "삭제된 코멘트입니다"`로 응답하고 원문은 보존한다.
같은 계정 재참여 시 기존 순서·코멘트를 복구한다. `profileAvailable: true`일 때
`GET /api/v1/members/{memberId}/social-profile`(#94)로 이동한다.

`canEdit/canDelete`는 현재 OWNER만 true다. `canReport`는 현재 번치가 있고 OWNER 관리
대상이 아닐 때 true, `canLike/canComment`는 현재 번치가 있을 때 true다. 화면 진입 시점의
힌트이며 모든 쓰기에서 다시 검사한다. 소속 0개는 공개 읽기만 허용한다.
`likeCount/likedByCurrentBunch/commentCount`는 별도 소셜 서비스의 접근 필터가 적용된 값이다.
`comments`는 기존 참여자 코멘트이고 소셜 댓글 목록은 #90 별도 API를 사용한다.
삭제된 소셜 댓글 자리도 commentCount에 포함한다.

## 삭제·검증

OWNER 삭제 시 번들 하위 사진 연결·참여자 코멘트·반응·소셜 댓글은 FK CASCADE로 정리한다.
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
