# 피드 공통 서버·DB 계약

이슈 #87(A), FEED-01·FEED-06. 정책은 [feed](feed.md)를 따른다.
이번 변경은 공통 접근 경계와 기존 발행 번들 조회 연결을 제공한다.
`GET /api/v1/feed`·소셜 HTTP API·광고·장소는 후속 이슈이며 이 문서는 미구현 API를 선언하지 않는다.

## 호출 인터페이스

`com.bunch.feed.service.FeedAccessService`는 Spring bean이다. 조회는 read-only 트랜잭션을
사용하고 호출자가 연 트랜잭션에는 참여한다. 아래 타입은 서버 내부 DTO이며 HTTP 응답에 직렬화하지 않는다.

| 메서드 | 반환·의미 |
| --- | --- |
| `viewer(userId: Long)` | `FeedViewer(memberId: Long, currentBunchId: Long?)`. 활성·가입 완료 검증과 서버 현재 번치 조회. 피드의 모든 ACTIVE 소속 제외는 viewer에 소속 목록을 싣지 않고 DB `NOT EXISTS`로 적용하며, 소속 0개는 `currentBunchId:null` |
| `requireActor(userId: Long)` | 현재 ACTIVE `BunchMembership`. 소속 0개는 `CURRENT_BUNCH_REQUIRED` |
| `readPublishedBundle(userId: Long, bundleId: Long)` | 현재 번치·공개/팔로우·차단·신고/운영 숨김을 통과한 실제 `Bundle`. 피드 전용 소속 제외는 적용하지 않음 |
| `requireInteraction(userId: Long, bundleId: Long)` | `FeedBundleInteraction(bundle: Bundle, actor: BunchMembership)`. 현재 ACTIVE 소속과 상세 접근을 함께 검증. 좋아요·댓글·장소 콕 등에서 사용 |
| `requireBundleReportTarget(userId: Long, bundleId: Long)` | 같은 DTO. **번들 재신고 접수 전용**으로 그 번들의 reporter-scoped 숨김만 무시. 전역 운영 숨김·프로필 신고·차단·공개범위는 계속 적용. 첫 신고 뒤에도 허용된 두 번째 신고를 접수하기 위한 검사이며 본문 조회에 사용 금지 |
| `visibleBundleIds(userId: Long, bundleIds: Collection<Long>, forFeed: Boolean = true)` | DB에서 접근 가능한 ID 집합. 기본은 모든 ACTIVE 소속 번치를 제외. `false`는 상세 후보 검사. 최대 500개, 양수 ID, 빈 입력은 빈 집합. 집합 순서는 미보장 |
| `visibleBunchIds(userId: Long, bunchIds: Collection<Long>)` | 실제 존재하는 번치 중 양방향 차단·프로필 신고·번치 숨김을 통과한 ID 집합. 좋아요·댓글 작성 번치 필터에 사용. 비공개 작성 번치에 대한 팔로우는 요구하지 않음. 최대 500개, 양수 ID |
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
C는 발행 상세를 조립할 때 위 공통 검사를 연결하고 A의 `BunchPublicService` 변경을 유지해야 한다.
운영 숨김을 초안 편집권 등 다른 권한으로 대체하지 않는다.

## 페이지 DB 조건 재사용 (#95)

`com.bunch.feed.domain.FeedAccessRepository`의 `companion object`는 아래 JPQL 상수를 제공한다.

- `VISIBLE_BUNCH`: 별칭 `bunch`, 파라미터 `currentBunchId: Long?`.
  양방향 차단·기존 프로필 신고·번치 운영/현재 번치 숨김 검사.
- `PUBLISHED_VISIBILITY`: 위 조건 + 별칭 `bundle`의 PUBLISHED 상태·현재 번치 내부/
  외부 PUBLIC 및 공개 번치/APPROVED 팔로우 검사. reporter-scoped 번들 숨김만 빠져 있으므로 일반 조회에 단독 사용 금지.
- `PUBLISHED_ACCESS`: 위 조건 + 번들 운영/현재 번치 숨김 검사.
- `FEED_EXCLUSIONS`: 별칭 `bunch`, 파라미터 `memberId: Long`의 모든 ACTIVE 소속을 DB `NOT EXISTS`로 제외.

피드 페이지 조회는 실제 `Bundle bundle, Bunch bunch`에서 `bunch.id = bundle.bunchId`와
`PUBLISHED_ACCESS AND FEED_EXCLUSIONS`를 **LIMIT/커서/count 이전**에 적용한다.
구간별 기간·APPROVED 팔로우·공개 번치·anchor 조건은 #95가 추가한다.
`visibleBundleIds`로 이미 자른 페이지를 사후 필터링하면 누락·빈 페이지·잘못된 hasNext가 생기므로 금지한다.
`viewer`의 `memberId/currentBunchId`를 요청마다 다시 얻고 클라이언트의 viewer ID로 바꾸지 않는다.

댓글·좋아요 집계는 실제 자기 테이블의 작성 번치에 `VISIBLE_BUNCH`를 적용한다.
댓글 타깃 숨김도 count와 LIMIT 이전 `NOT EXISTS FeedContentHide`로 제외한다.
삭제된 댓글 자리 포함 규칙과 답글 부모 처리 등은 E/F가 소유한다.

## V23 `feed_content_hides`

| 컬럼 | 계약 |
| --- | --- |
| `id BIGINT` | identity PK |
| `target_kind VARCHAR(16)` | `BUNCH`, `BUNDLE`, `COMMENT`만 허용. COMMENT는 앞으로 생기는 소셜 댓글/답글 ID 공간이며 기존 `bundle_comments` 코멘트가 아님 |
| `target_id BIGINT` | 양수 대상 ID. 종류와 함께 식별. 다형 참조이므로 대상 FK는 없음 |
| `reporter_bunch_id BIGINT NULL` | null이면 운영 전역 숨김, 값이 있으면 이 신고 번치가 현재 번치일 때만 비노출. `bunches(id) ON DELETE CASCADE` |
| `reason TEXT` | 비어 있지 않은 사유. 신고 writer는 `REPORTED`, 운영은 검토 사유를 직접 기록 |
| `created_at TIMESTAMPTZ` | NOT NULL, 기본 DB now(). 중복 숨김은 최초 기록·시각 유지 |

`UNIQUE NULLS NOT DISTINCT(target_kind, target_id, reporter_bunch_id)`로 전역 한 건과
신고 번치별 한 건을 각각 보장한다(PostgreSQL 16). 다른 kind나 reporter는 별개다.
기존 `bunch_profile_reports`의 이력은 검토 상태와 관계없이 비노출 근거이며 복사·재작성하지 않는다.

`FeedContentHideRepository.hideForReporter(kind: FeedContentKind, targetId: Long, reporterBunchId: Long): Int`는
**이미 시작한 쓰기 트랜잭션 필수(MANDATORY)**이며 `INSERT ... ON CONFLICT DO NOTHING`으로
신규 1/기존 0을 반환한다. F(#91)는 신고 접수·한도 검증·새 신고 row 저장이 성공한 같은
트랜잭션에서 호출한다. 재신고는 새 신고 ID를 만들지만 숨김 row는 중복하지 않는다. 실패 시 함께 롤백한다.
이 writer는 신고 대상의 존재·권한·24시간 한도를 판정하지 않으며 신고 대신 단독 호출하지 않는다.

페이지·집계는 전역·현재 신고 번치의 숨김을 제외하는 `NOT EXISTS` 조건을 자기 도메인의 실제 DB 쿼리에 넣어야 한다.

### 삭제·보존·참조 경계

- 다형 target에는 FK가 없고 미래 댓글 테이블도 참조하지 않는다. target ID는 종류별 identity이며
  삭제 후 재사용하거나 sequence를 되감아 새 콘텐츠에 옛 ID를 붙이지 않는다.
- **A의 현재 보존 정책:** 번들·번치 target의 영구 삭제 후에도 그 target을 가리키는 숨김 row는
  남긴다. 존재하는 실제 콘텐츠를 먼저 조회하므로 orphan은 콘텐츠를 되살리거나 허용하지 않는다.
  target 삭제만으로 다른 번치의 신고 이력·숨김이 변경되지 않는다. 별도 TTL·자동 purge는 없다.
- reporter 번치가 삭제되면 그 번치가 신고한 숨김 row만 FK CASCADE로 제거한다.
  같은 target의 다른 reporter row와 전역(null reporter) row는 그대로 남는다.
- F는 실제 소셜 댓글 **soft delete** 시 숨김을 제거하지 않는다. 삭제 자리도 차단/신고/운영 숨김이
  우선하며, 숨김을 해제해도 실제 soft-delete 본문을 복원하지 않는다.
- C/F는 번들 영구 삭제 시 실제 번들·댓글의 생애주기와 위 **target 숨김 보존** 정책을 연결한다.
  이 테이블에 댓글이 있다고 미래 댓글을 생성하거나 가짜 adapter로 대체하지 않는다.
  향후 물리 정리를 도입하려면 실제 대상 테이블과 삭제 트랜잭션/보존 요구를 근거로 새 계약·테스트를 추가한다.
- 전역 운영 숨김 해제는 해당 null-reporter row만 삭제한다. reporter 숨김·기존 프로필 신고는
  남으므로 계속 비노출일 수 있다. 신고 자체를 이유로 전역 row를 만들지 않는다.

운영 예시(신뢰된 DB 운영 경로에서만 수행):

```sql
INSERT INTO feed_content_hides(target_kind, target_id, reason)
VALUES ('BUNDLE', :bundle_id, :review_reason)
ON CONFLICT (target_kind, target_id, reporter_bunch_id) DO NOTHING;

DELETE FROM feed_content_hides
WHERE target_kind = 'BUNDLE' AND target_id = :bundle_id AND reporter_bunch_id IS NULL;
```

운영자 인증·API·심사 화면·자동 제재·신고 접수 테이블은 이번 변경에 포함하지 않는다.
전역 해제는 보존된 콘텐츠의 조회만 복구하며 삭제·계정 상태·공개 범위·차단을 우회하지 않는다.
