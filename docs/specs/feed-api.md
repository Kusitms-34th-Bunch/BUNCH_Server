# 피드 공통 서버·DB 계약

이슈 #87(A)·#95(B), FEED-01·FEED-06. 정책과 6장 기능 색인은 [feed](feed.md)를 따른다.
공통 접근 경계와 실제 `GET /api/v1/feed` 조립 계약이다.
소셜·광고·장소의 쓰기 API는 기능별 Spec을 따른다.
신고 노출·운영 삭제는 2026-11-22 사용자 결정(#207)의 [신고 계약](feed-reports.md)이 우선한다.
아래는 변경 후 계약이며 이번 문서 갱신은 구현·검증 완료를 뜻하지 않는다.

## GET `/api/v1/feed`

기존 access JWT와 Redis 세션 검증을 사용한다. 응답은 공통 `success / data / error`다.
클라이언트의 현재 번치·회원 ID는 받지 않는다.
2026-11-21 사용자 결정에 따라 소속을 이유로 제외하는 대상은 서버 선택 `currentBunchId`뿐이다.
다른 ACTIVE 소속 번치는 외부와 동일하게 PUBLIC 발행 번들 중 공개 번치 또는 현재 번치의
APPROVED 팔로우 대상만 허용하며 차단·레거시 전역 BUNCH/BUNDLE 숨김을 검사한다. 소속만으로 비공개 접근을 허용하지 않는다.
`currentBunchId=null`이면 현재 번치 제외 없이 공개 구간을 조회한다.

| 입력 | 계약 |
| --- | --- |
| `size` | 번들 수, 기본 20, 1..50. 광고·구간 안내는 미포함. 이어받을 때 변경 가능 |
| `cursor` | 생략하면 새 조회. 응답의 `nextCursor`를 그대로 전달. 빈 문자열·해석 불가·잘못된 구조는 400 |

`data`는 `{anchorTime, currentBunchId, canInteract, size, bundleCount, items,
nextCursor, hasNext, endReached}`다. `bundleCount`는 이번 응답의 실제 번들 수다.
`items`는 표시 순서대로 다음 세 종류를 포함한다.

| `type` | 데이터·표시 위치 |
| --- | --- |
| `BUNDLE` | `segment: FOLLOWING / PUBLIC`, `bundle` 카드 |
| `ADVERTISEMENT` | `advertisement`: [광고 응답](feed-ads.md). 해당 번들 바로 뒤 |
| `FOLLOWING_CAUGHT_UP` | 팔로잉 구간 끝 안내 위치. 마지막 팔로잉 번들과 그 뒤 광고 이후, 첫 공개 번들 이전 |

카드는 `{id, bunch, publishedAt, title, date, coverImageUrl, photos, locations,
participants, likeCount, likedByCurrentBunch, commentCount}`다.
`bunch`는 `{id, name, imageUrl, characterType, visibility}`이며 번치 프로필 이동에 쓴다.
`coverImageUrl`과 `photos[].imageUrl`은 카드·Feed 표시용 `thumbnail.jpg`의 정확히 1800초 유효한
signed URL이고, `bunch.imageUrl`과 `participants[].imageUrl`은 `profile.jpg` signed URL이다.
`photos`는 `{id, imageUrl, position}` 배열, `position ASC, id ASC`이며 첫 사진이 대표다.
`date`는 nullable ISO 날짜, 경과 시간은 `publishedAt` 기준으로 앱이 표시한다.
`locations`는 [장소 계약](bundle-locations.md)의 순서 있는 배열이다.
`participants`는 [발행본 계약](published-bundle-edit.md)의 공통 표시 DTO
`{memberId, joinedAt, nickname, imageUrl, profileAvailable}`다. 발행 참여자 전원을 유지하며
나간/탈퇴한 사람은 `작성자 / null / false`, 같은 계정의 유효한 재가입은 복구된다.
`profileAvailable=true`일 때만 [멤버 프로필](member-social-profile.md)로 이동한다.

이미지 객체는 비공개이며 기존 Feed 접근 확인 뒤 URL을 서명한다. 서명 URL은 DB·커서·멱등 결과·
이벤트에 저장하지 않는다. 만료·서명 실패 시 전용 갱신 API가 아니라 같은 Feed GET을 다시 호출한다.

`hasNext=true`와 `nextCursor!=null`은 항상 함께 반환한다. `endReached=!hasNext`다.
모든 후보가 없으면 `items=[]`, `bundleCount=0`, `hasNext=false`, `nextCursor=null`,
`endReached=true`이며 앱은 「모든 피드를 확인했어요」만 표시한다.
팔로잉 카드가 한 번도 없으면 `FOLLOWING_CAUGHT_UP`을 내보내지 않는다.
팔로잉을 이전 페이지에서 보았으나 남은 후보가 숨겨졌다면 다음 응답 맨 앞에 안내를 둔다.
팔로잉이 페이지 마지막에서 정확히 끝나도 공개 후보를 한 건 조회해 `hasNext`를 판정한다.
공개 후보가 있으면 다음 커서는 PUBLIC의 시작점이며 첫 공개 카드를 건너뛰지 않는다.

### 순서·커서 문맥

첫 요청의 `anchorTime`을 마이크로초 정밀도로 고정한다. 팔로잉은
`[anchorTime-14일, anchorTime]` 안의 APPROVED 팔로우 대상 PUBLIC 발행 번들이다.
그 뒤 공개 번치의 PUBLIC 발행 번들을 조회하되 같은 기간의 팔로잉 후보는 제외한다.
각 구간은 `publishedAt DESC, id DESC`, 다음 페이지는 이 튜플보다 엄격히 작은 값이다.
공개 구간에서는 14일보다 오래된 팔로잉 번들도 볼 수 있다. 새 발행은 다음 새 조회에서 보인다.

커서는 버전이 있는 Base64URL 내부 구조이며 앱이 해석·수정하지 않는다.
`segment / anchorTime / lastTime / lastId / viewerId / currentBunchId /
membershipContext / followingContext / followingSeen / bundleCount / lastAdId`를 담는다.
공개 시작점만 lastTime/lastId가 함께 null이다. bundleCount는 전체 누적 번들 수,
lastAdId는 마지막 실제 광고 ID다. JWT 비밀키·암호화 키·콘텐츠 snapshot·조회 이력 DB·세션 캐시는 추가하지 않는다.
커서는 접근 자격 증명이 아니므로 파싱 이후에도 모든 DB 접근 조건을 적용한다.

membershipContext는 정렬된 ACTIVE 소속의 ID·번치 ID·lastUsedAt 지문이다.
followingContext는 현재 번치의 정렬된 APPROVED 관계 ID·대상 ID·상태·승인 시각 지문이다.
2026-11-21 피드 제외 변경 후에도 두 지문과 아래 무효화 조건은 그대로 유지한다.
현재 선택/소속 또는 팔로우 생성·취소·승인·재생성으로 지문이 달라지면
`409 FEED_001 / error.feed_cursor_context_changed`다. 앱은 현재 목록을 새 cursor 없는
조회로 갱신한다. 시트의 「팔로우 변경은 다음 새로고침 때 반영」은 이 갱신 경로로 충족한다.
다른 회원의 커서·미래 anchor·짝이 맞지 않는 튜플·음수/과대 count 등은 `400 COMMON_001`이다.
페이지 크기 변경은 문맥 변경이 아니다.

차단·레거시 전역 BUNCH/BUNDLE 숨김·삭제·공개 범위는 매 요청 최신 DB 조건으로 LIMIT 전에 검사한다.
이 변화만으로 지문을 고정하거나 사후 필터링하지 않는다. 한 응답의 후보와 카드는
REPEATABLE_READ 트랜잭션에서 조립해 원자적 발행 수정 전후를 섞지 않는다.
V31의 PUBLIC/PUBLISHED 부분 인덱스 `(published_at DESC, id DESC)`가 실제 keyset 조회를 지원한다.

### 실제 배치 조립·광고·헤더

최대 50개 번들에 대해 사진+이미지, 번치 프로필, C의
`BundleAttributionService.publishedAll`, L의 `BundleLocationService.listForBundles`,
D의 `SocialLikeService.summaries`, E의 `SocialCommentService.counts`를 각각 배치 호출한다.
댓글 수는 운영 삭제를 포함한 삭제 자리와 답글을 센다. 작성 번치 차단·레거시 전역 BUNCH
숨김과 그로 인해 보이지 않는 부모의 답글은 제외한다. 신고는 집계를 바꾸지 않는다.
단일 카드용 상세 API를 반복 호출하지 않는다.

누적 6번들마다 광고 자리를 계산하고 매 요청 H의 실제
`AdvertisementService.selectForFeed(userId, bundleCount, slots, lastAdId)`를 한 번 호출한다.
slots는 현재 페이지 번들 기준 1-based 위치다. 구간과 페이지를 넘어 누적하며 size에서 제외한다.
응답 내 중복 회피·직전 광고 회피·대안 없는 자리 생략·회원 개인 숨김은 H가 처리한다.
번들 0개면 광고도 0개다. 같은 입력 cursor의 재요청은 번들이 같아도 광고가 달라질 수 있다.

소속 0개는 PUBLIC만 조회하고 currentBunchId=null, canInteract=false다.
헤더의 번치 프로필과 미확인 알림은 기존 [홈·프로필](bunch-home-profile.md)·
[알림 API](notification-api.md)를 재사용하며 피드에서 새 집계 경로를 만들지 않는다.

알림 이동/전송도 공통 접근 정책을 적용한다. 실제 알림 target의 `sourceBunchId`는
반응 작성 번치, `commentId`는 소셜 댓글/답글이며 번들 소유 번치와 구별한다.
명시적 열기는 현재 서버 번치(소속 0개면 null)로 공개/승인·차단·레거시 전역 숨김을 재검사한다.
푸시 전달은 저장된 수신 번치의 ACTIVE 소속과 문맥을 사용하고, 실제 댓글 및 원댓글의
삭제·작성 번치 접근 제한도 전송 직전에 다시 확인한다. 신고는 열기·전송을 막지 않는다.
원문 토큰이나 알림 target을 접근 권한으로 쓰지 않는다.

## 호출 인터페이스

`com.bunch.feed.service.FeedAccessService`는 Spring bean이다. 조회는 read-only 트랜잭션을
사용하고 호출자가 연 트랜잭션에는 참여한다. 아래 타입은 서버 내부 DTO이며 HTTP 응답에 직렬화하지 않는다.

| 메서드 | 반환·의미 |
| --- | --- |
| `viewer(userId: Long)` | `FeedViewer(memberId: Long, currentBunchId: Long?)`. 활성·가입 완료 검증과 서버 현재 번치 조회. 피드는 이 `currentBunchId`만 DB 조건으로 제외하며, 소속 0개는 `currentBunchId:null` |
| `requireActor(userId: Long)` | 현재 ACTIVE `BunchMembership`. 소속 0개는 `CURRENT_BUNCH_REQUIRED` |
| `readPublishedBundle(userId: Long, bundleId: Long)` | 현재 번치·공개/팔로우·차단·레거시 전역 BUNCH/BUNDLE 숨김을 통과한 실제 `Bundle`. 피드 전용 현재 번치 제외는 적용하지 않음 |
| `requireInteraction(userId: Long, bundleId: Long)` | `FeedBundleInteraction(bundle: Bundle, actor: BunchMembership)`. 현재 ACTIVE 소속과 상세 접근을 함께 검증. 좋아요·댓글·장소 콕 등에서 사용 |
| `requireBundleReportTarget(userId: Long, bundleId: Long)` | 같은 DTO. **번들 신고 접수 전용**이며 일반 상세와 같은 접근 조건. 신고 이력·non-null reporter 숨김은 두 경로 모두 무시. 차단·공개범위·삭제·레거시 전역 BUNCH/BUNDLE 숨김은 유지. 대상별 신고 권한·한도는 신고 서비스가 검증 |
| `visibleBundleIds(userId: Long, bundleIds: Collection<Long>, forFeed: Boolean = true)` | DB에서 접근 가능한 ID 집합. 기본은 현재 `currentBunchId`만 제외하고 다른 소속은 외부 접근 조건 적용. 현재 번치가 없으면 공개 후보 조회. `false`는 상세 후보 검사. 최대 500개, 양수 ID, 빈 입력은 빈 집합. 집합 순서는 미보장 |
| `visibleBunchIds(userId: Long, bunchIds: Collection<Long>)` | 실제 존재하는 번치 중 양방향 차단·레거시 전역 BUNCH 숨김을 통과한 ID 집합. 좋아요·댓글 작성 번치 필터에 사용. 비공개 작성 번치에 대한 팔로우는 요구하지 않음. 최대 500개, 양수 ID |
| `publishedBundles(userId: Long, bunchId: Long, pageable: Pageable)` | 기존 프로필 발행 번들 목록용 `Page<Bundle>`. 숨김을 LIMIT·count 이전 DB 조건으로 적용. 현재 번치라면 PRIVATE도 포함. 호출자가 페이지 입력 제한을 검증 |

조회와 쓰기에서 같은 `actor.bunch.id`를 사용한다. 상호작용 검사는 현재 번치를 한 번 선택한 뒤
그 선택으로 번들 접근을 검사한다. 쓰기 소유자는 기존 기능의 잠금 순서와 현재 소속 재검증을
자기 트랜잭션에서 수행하고 `requireInteraction`을 호출해야 한다. 이 서비스는 쓰기·잠금·
역할 판정·동시 탈퇴/차단과의 직렬화·멱등성·알림을 수행하지 않는다. DTO를 요청 간 보관하지 않는다.
삭제된 댓글 작성자의 삭제 권한, 번들 OWNER 신고 금지 등 대상별 규칙은 해당 서비스가 검증한다.

### 오류·기존 경로

| 조건 | 기존 오류 코드 / HTTP |
| --- | --- |
| 계정 없음 / 비활성 / 가입 중 | `USER_001` 404 / `AUTH_001` 401 / `AUTH_015` 403 |
| 번치 명의 행동인데 현재 ACTIVE 소속 없음 | `BUNCH_010` 409 |
| 양방향 차단 / 대상 번치 없음 | `BUNCH_009` 404 |
| 비공개 번치의 미승인 외부 조회 | `AUTH_002` 403 |
| 번들 삭제·비발행·숨김·외부 멤버 전용 | `RESOURCE_001` 404 |
| 양수 ID·후보 집합 한도 위반 | `COMMON_001` 400 |
| 기존 내보내짐/삭제 안내가 남아 있음 | 기존 `BUNCH_008` 409와 notice 상세 유지 |

`BunchPublicService`의 기존 `/api/v1/bunches/{bunchId}/published-bundles` 목록·상세는
공통 경계로 연결했다. 기존 응답 모양과 현재 번치 내부/다른 소속 외부 구분은 유지한다.
`BundleService`·`BundleAccessService`의 내부 편집/조회 경로는 C(#88)가 소유한다.
발행 상세는 위 공통 검사를 연결하며 `BunchPublicService`도 같은 경계를 유지한다.
레거시 전역 숨김을 초안 수정 권한 등 다른 권한으로 대체하지 않는다.

## 페이지 DB 조건 재사용 (#95)

`com.bunch.feed.domain.FeedAccessRepository`의 `companion object`는 아래 JPQL 상수를 제공한다.

- `VISIBLE_BUNCH`: 별칭 `bunch`, 파라미터 `currentBunchId: Long?`.
  양방향 차단·레거시 전역 BUNCH 숨김 검사. 모든 `BunchProfileReport` predicate와
  non-null reporter 숨김 조건은 제거한다.
- `PUBLISHED_VISIBILITY`: 위 조건 + 별칭 `bundle`의 PUBLISHED 상태·현재 번치 내부/
  외부 PUBLIC 및 공개 번치/APPROVED 팔로우 검사. 번들 전역 숨김 검사가 빠져 있으므로 일반 조회에 단독 사용 금지.
- `PUBLISHED_ACCESS`: 위 조건 + 레거시 전역 BUNDLE 숨김 검사.
- `FEED_EXCLUSIONS`: 별칭 `bunch`, 파라미터 `currentBunchId: Long?`.
  조건은 `(:currentBunchId is null or bunch.id <> :currentBunchId)`이며 현재 번치만 제외한다.

Repository의 `following`·`publicBundles`·`visibleBundleIds`는 위 `currentBunchId`를 기준으로
조회하며 피드 제외를 위한 `memberId`는 받지 않는다. 서비스의
`FeedAccessService.visibleBundleIds(userId: Long, bundleIds: Collection<Long>, forFeed: Boolean = true)`는
회원 자격과 서버 현재 번치를 검증한다. 커서의 membership/following 문맥은 유지한다.

피드 페이지 조회는 실제 `Bundle bundle, Bunch bunch`에서 `bunch.id = bundle.bunchId`와
`PUBLISHED_ACCESS AND FEED_EXCLUSIONS`를 **LIMIT/커서/count 이전**에 적용한다.
구간별 기간·APPROVED 팔로우·공개 번치·anchor 조건은 #95가 추가한다.
`visibleBundleIds`로 이미 자른 피드 페이지를 사후 필터링하면 누락·빈 페이지·잘못된 hasNext가 생기므로 금지한다.
[콕 목록](pinned-places.md)의 #125는 원본 불가 핀도 유지하므로 해당 핀 페이지를 거르지 않고
`forFeed=false` 배치 결과로 출처 접근 상태만 조립한다. 유효한 내보내짐/삭제 안내도 배치 검사해
상세에서 거부할 출처 ID·이름·제목을 숨긴다. 카드 탭은 기존 발행본 상세에서 다시 검사한다.
`viewer`의 `memberId/currentBunchId`를 요청마다 다시 얻고 클라이언트의 viewer ID로 바꾸지 않는다.

댓글·좋아요 집계는 실제 자기 테이블의 작성 번치에 `VISIBLE_BUNCH`를 적용한다.
댓글 타깃의 `FeedContentHide` predicate는 제거한다. 댓글 삭제는 행을 제외하지 않고
삭제 자리로 표시하며, 신고 이력과 non-null reporter 숨김은 모든 조회·집계에서 무시한다.
삭제된 댓글 자리 포함 규칙과 답글 부모 처리 등은 E/F가 소유한다.

## V23 `feed_content_hides` 호환 경계 (#207)

기존 스키마는 유지하되 새 신고·운영 조치의 저장소로 쓰지 않는다.

| 컬럼 | 호환 계약 |
| --- | --- |
| `id BIGINT` | identity PK |
| `target_kind VARCHAR(16)` | 기존 BUNCH/BUNDLE/COMMENT. COMMENT는 실제 소셜 댓글·답글 ID |
| `target_id BIGINT` | 양수 다형 대상 ID, 대상 FK 없음. 삭제 후 ID 재사용 금지 |
| `reporter_bunch_id BIGINT NULL` | null인 기존 BUNCH/BUNDLE 행만 전역 숨김으로 적용. non-null 파생 행은 V52에서 제거하고 배포 중 잔존 행도 모든 경로에서 무시. 기존 FK는 유지 |
| `reason TEXT` | 기존 사유 보존. 새 신고·운영 숨김 writer 없음 |
| `created_at TIMESTAMPTZ` | 기존 기록 시각 보존 |

기존 `UNIQUE NULLS NOT DISTINCT(target_kind, target_id, reporter_bunch_id)`는 유지한다.
`bunch_profile_reports`는 상태와 무관하게 조회·집계·접근의 제외 근거가 아니며 신고 이력으로 보존한다.
`FeedContentHideRepository.hideForReporter` 호출을 중단하고 불필요해진 repository writer를 제거한다.
쿼리 상수는 레거시 null-reporter BUNCH/BUNDLE 숨김만 LIMIT·count 이전에 제외한다.
이 규칙은 피드·상세·프로필·좋아요·댓글·콕 출처·알림 열기와 푸시 재검사에 동일하게 적용한다.

### V52·삭제·보존

V52는 non-null reporter 파생 숨김을 모두 제거하고 신고 이력은 보존한다.
전역 COMMENT 숨김은 실제 댓글 soft delete로 변환한 뒤 해당 숨김 행을 제거한다.
세부 변환·신고 이력 보존은 [신고 전환 계약](feed-reports.md)을 따른다.
댓글 삭제 자리는 댓글 타깃 숨김으로 사라지지 않으며 답글·집계 포함 규칙을 유지한다.

번들·번치 영구 삭제 후 남는 다형 target 숨김 행은 기존 보존 정책을 유지한다.
실제 콘텐츠가 없으면 접근 불가다. 구버전 앱 배포 중 잔존하는 non-null 행도 효과가 없다.
레거시 전역 BUNCH/BUNDLE 숨김 해제는 해당 null-reporter 행만 제거하며,
신고 이력은 복구를 막지 않는다. 삭제·차단·공개 범위 등 실제 접근 제한은 계속 적용한다.

새 위반 조치는 신뢰된 내부 서비스의 댓글·번들 삭제로 처리한다([신고 계약](feed-reports.md)).
전역 숨김을 새로 쓰는 SQL 운영 절차나 관리자 인증·API·화면은 추가하지 않는다.
