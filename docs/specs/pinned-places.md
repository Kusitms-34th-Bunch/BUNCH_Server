# 장소 콕하기 계약

#92·#125, FEED-10, 승인한 6.3.1 및 8.1.1~8.1.9의 서버 범위를 구현한다.
[피드 접근](feed-api.md)과 [번들 장소](bundle-locations.md) 계약을 따른다.
8장 화면 UI는 별도 범위이며, 주소 없는 지역 계약은
[#126 장소 검색 계약](place-search.md)을 따른다.
8.1.8의 되돌리기는 2026-11-08 승인으로 앱의 삭제 요청 지연 방식으로 처리한다.

## 코드 용어

제품 용어 「콕」은 번치가 장소를 저장한 기록이며 코드에서도 `Kok`으로 표현한다.
핀 기능 타입·DTO·테스트는 `com.bunch.kok` 패키지를 사용한다.
장소 자체의 공통 값인 `PlaceDetails`는 `com.bunch.place.domain`에 유지하고
번들 장소·광고·콕이 함께 사용한다. 같은 저장 기록을 Kok/PinnedPlace 별칭으로 중복 정의하지 않는다.

이번 이름 정렬은 Kotlin 타입·패키지와 JPQL 엔티티 참조에 한정한다.
기존 REST 경로 `pinned-places`, JSON의 `pinned / pinId / pinnedByMemberId / pinnedAt`,
DB 테이블 `pinned_places`와 컬럼·인덱스·Flyway 이력은 유지한다.
저장·권한·중복·삭제·페이지·응답 형식 변경이나 추가 마이그레이션은 없다.

## API

활성 JWT session과 공통 `success / data / error` 형식을 사용한다.
현재 번치는 서버 선택값이며 클라이언트가 저장 대상 번치를 지정하지 않는다.

- `GET /api/v1/bundles/{bundleId}/pinned-places`: 매번 발행본 접근 검사 후
  `{bundleId, currentBunchId, canPin, locations}` 반환. locations는 position 순서이며
  `{id, provider, externalId, name, address, position, pinned, pinId}`다.
  가입 완료 소속 0개 사용자는 공개 장소를 조회할 수 있고 canPin=false, pinId=null이다.
- `PUT /api/v1/bundles/{bundleId}/locations/{locationId}/pin`: 본문 없이 저장된 장소를 콕한다.
  실제 발행 번들과 장소 소속·공개범위·차단·신고·운영 숨김을 쓰기 잠금 뒤 재검사한다.
  이름·주소·provider·externalId는 서버 장소 행에서만 복사하며 요청 본문은 사용하지 않는다.
  응답은 아래 핀 스냅샷이다. 중복 요청도 HTTP 200이며 기존 핀을 반환한다.
- `DELETE /api/v1/pinned-places/{pinId}`: 현재 번치의 핀을 취소하고 data=null 반환.
  다른 멤버가 등록한 핀도 취소할 수 있다. 이미 없거나 다른 번치 소유 ID도 멱등 200이다.
  원본 번들 접근이 사라져도 저장된 핀의 취소는 가능하다.

8.1.7의 주소 복사는 앱이 응답의 전체 주소를 클립보드에 넣는 동작이며 서버 요청이 없다.
주소 없는 지역은 `address: null`로 저장·응답하고 앱은 장소명을 복사한다.
장소명으로 저장된 주소를 채우지 않는다. 번들·광고의 동일 nullable 계약은 #126을 따른다.

핀 응답: `{id, bunchId, provider, externalId, name, address, sourceType,
sourceBundleId, sourceLocationId, sourceAdvertisementId, pinnedByMemberId, pinnedAt}`.
sourceType은 BUNDLE 또는 ADVERTISEMENT다. 원본 삭제·개인 계정 삭제 후 참조는 null일 수 있다.

잘못된 양수 ID는 COMMON_001/400, 인증 실패 AUTH_001/401,
가입 미완료 AUTH_015/403, 소속 0개 쓰기는 BUNCH_010/409다.
숨겨진/없는 번들·장소 또는 장소의 번들 소속 불일치는 RESOURCE_001/404다.
비공개 번치·차단·내보내짐 안내는 공통 피드 접근 오류를 그대로 사용한다.

## 현재 번치 목록 — #125

근거: [콕 시트](https://docs.google.com/spreadsheets/d/1ScziF7WoYLHRiC_UYJBkKsi0jL6KKQJD2TNDzW1QGfU/edit?gid=1448357555#gid=1448357555)의
J2(현재 번치·역할 무관), I3/J3/K3(멤버·개수·이탈), I4/J4(카드·정렬·출처),
J6/J7(접근 재검사·복구), J8/K8(주소 복사), I9/J9/K9(삭제·되돌리기), I10(빈 상태).
2026-11-11 최신 셀을 다시 조회해 대조했다. GUI3는 로그인 문제로 확인한 화면 근거가 없으며
시트의 서버 계약에 한정한다. 주소 없는 지역은 선행 #126 계약대로 `address: null`을 허용하고 빈 문자열·공백은 거부한다.

### 최소 실행 계획·유지 경계

1. 기존 핀·접근·멤버 조회와 DELETE 회귀 테스트를 유지하고 목록 실패·경계 테스트를 추가한다.
2. 기존 Controller/Service/Repository/DTO에 현재 번치 목록을 추가한다.
   장소·최초 출처는 기존 행을 재사용하고 출처 접근·현재 이름만 일괄 조립한다.
   번치 이름은 기존 `FeedQueryRepository.bunches`를 재사용해 이미지 개별 조회를 피한다.
3. 조회 인덱스만 새 V2026111111270로 추가한다. 기존 적용 파일·저장 모델·삭제 listener는 바꾸지 않는다.
   최신 dev의 V35 푸시 설정 이력을 유지하고 #125는 V2026111111270을 사용한다.
4. 전용 PostgreSQL/Redis 관련 테스트 후 공용 잠금으로 전체 clean build를 실행한다.

정리·전체 리팩토링은 포함하지 않는다. 새 의존성·캐시·복구 API·실시간 엔진은 없다.

### GET `/api/v1/pinned-places`

활성 session·가입 완료·서버 현재 ACTIVE 번치 소속이 필요하다. 다른 번치 ID를 받지 않는다.
현재 번치가 없으면 `BUNCH_010/409`, 가입 미완료·인증 오류는 기존 계약 그대로다.
서버 현재 번치를 매 요청 재확인하므로 번치 전환 후 이전 커서로 타 번치를 읽을 수 없다.

| 입력 | 계약 |
| --- | --- |
| `memberId` | 생략하면 전체. 양수 개인 계정 ID이며 최초 등록자로 필터링한다. 현재 멤버 칩에 없는 ID는 전체로 돌아가며 이전 커서도 버린다. 이탈/탈퇴 멤버 칩 새로고침과 같은 처리다. |
| `size` | 기본 20, 1..50. 계속 불러올 때 변경 가능 |
| `cursor` | 생략하면 첫 페이지. 응답 값을 그대로 사용한다. 빈 값·잘못된 구조·다른 회원/현재 번치/필터 문맥은 `COMMON_001/400`. 앱은 새 조회로 갱신한다. |

`data`는 `{bunchId, selectedMemberId, totalCount, members, items, nextCursor}`다.
`selectedMemberId=null`은 전체이며 필터 이탈 때 앱의 선택 칩을 이 값으로 맞춘다.
`totalCount`는 필터와 관계없는 번치 전체 핀 수다. `members`는
`{membershipId, memberId, nickname, imageUrl, count}`이며 0개 멤버도 포함한다.
기존 멤버 관리 `findActiveMembers`의 OWNER → CO_OWNER → MEMBER, 닉네임·소속 ID 순서를 쓴다.
이탈·내보내짐·탈퇴 멤버 핀과 개인 계정 삭제로 등록자가 null인 핀은 전체에 남는다.
현재 계정의 재가입은 기존 핀 최초 등록자를 바꾸지 않으며 해당 계정 칩에서 다시 센다.

카드 `items`:
`{id, provider, externalId, name, address, pinnedByMemberId, pinnedAt, sourceType,
sourceAccessible, sourceBundleId, sourceBunchId, sourceBunchName, sourceBundleTitle}`.
장소 이름·주소는 등록 당시 스냅샷이다. `sourceType`은 기존 `BUNDLE / ADVERTISEMENT`를 유지한다.
앱은 ADVERTISEMENT를 **BUNCH SPOT**으로 표시하고 이동하지 않는다(`sourceAccessible=false`).
BUNDLE이 접근 가능할 때만 원본 번들·번치 ID와 현재 번치명·번들 제목을 제공한다.
원본 삭제·비공개·차단·신고·운영 숨김·유효한 내보내짐 안내로 접근 불가하면
`sourceAccessible=false`와 출처 4개 필드 null을 제공하고 핀은 그대로 남긴다.
다시 접근 가능해지면 다음 조회에 출처 표시가 복구된다. 상세 후보 일괄 검사
`FeedAccessService.visibleBundleIds(..., forFeed=false)`와 기존 안내 조건을 재사용한다.
현재 소속 번치의 발행본은 피드 제외 규칙 때문에 숨기지 않는다.

순서는 `pinnedAt DESC, id DESC`, 다음 페이지는 마지막 튜플보다 엄격히 작은 행이다.
최대 size+1개를 조회해 다음 페이지 유무를 결정하며 끝은 `nextCursor=null`, 빈 목록은 `items=[]`다.
커서는 버전·회원·현재 번치·필터·마지막 시각/ID의 Base64URL 구조다. 접근 자격 증명이 아니며
모든 쿼리에 현재 번치 조건을 적용한다. 삭제된 경계 핀을 다시 조회할 필요가 없다.
페이지 간 새 콕·삭제가 일어날 수 있고 새 콕은 새 조회에서 확인한다.
목록·개수·멤버·출처는 한 REPEATABLE_READ 트랜잭션의 스냅샷에서 조립한다.
V2026111111270는 실제 전체/멤버 페이지를 위한 `(bunch_id, pinned_at DESC, id DESC)`와
`(bunch_id, pinned_by_member_id, pinned_at DESC, id DESC)` 인덱스만 추가한다.

카드 탭은 기존 `GET /api/v1/bunches/{sourceBunchId}/published-bundles/{sourceBundleId}`의 발행본 상세 조회를 실행한다.
목록의 접근 가능 플래그는 권한 보장이 아니며 상세가 삭제·공개범위·차단 등을 다시 검사한다.
별도 원본 접근 확인 endpoint는 추가하지 않는다. 목록 뒤 접근 불가가 된 경우 상세 오류로
앱이 8.1.6 모달·카드 상태를 갱신하고, 네트워크 실패는 기존 화면을 유지한다.

모달·토스트·개수 낙관 갱신·클립보드·필터/스크롤 복귀·요청 중복 방지·삭제 보류는 앱 책임이다.
자동 테스트는 HTTP·저장소 계약을 검증하며 실제 iOS/WebView 수동 검증을 대체하지 않는다.
#107의 편집/발행, #122 참여자, #123 픽, #124 공개범위 수정은 이 목록에서 구현하지 않는다.
#124 등에서 공개범위를 바꾸면 기존 접근 정책을 통해 다음 목록/상세에 반영된다.

## 콕에서 빼기·되돌리기 경계

서버 삭제 API는 기존 `DELETE /api/v1/pinned-places/{pinId}` 하나를 재사용한다.
서버는 요청을 받으면 해당 현재 번치의 핀을 즉시 삭제하며, 복구 API·삭제 유예 타이머·
soft delete·되돌리기 토큰을 추가하지 않는다. 빈 성공 응답은 HTTP 200, `data:null`이다.

- 앱은 「콕에서 빼기」 시 카드와 필터 개수만 우선 변경하고, 되돌리기 토스트가
  유지되는 동안 DELETE 요청을 보내지 않는다.
- 토스트 안에서 되돌리면 보류 중인 삭제를 취소하고 기존 카드·개수를 복원한다.
  서버 행이 유지되므로 최초 등록자·시각·출처도 그대로다.
- 되돌리지 않고 토스트가 끝나면 보류한 정확한 `pinId`로 DELETE를 보낸다.
  요청 실패 시 로컬 표시를 복원하거나 최신 목록을 확인하고, 응답 유실 시 같은 ID로
  재시도할 수 있다. 삭제 후 PUT으로 재등록하는 방식은 되돌리기가 아니다.
- 삭제 요청 전까지 다른 멤버가 조회하면 기존 콕이 보인다. 같은 장소의 중복 콕도
  기존 행을 반환한다. 이 시간 동안 전역에서 즉시 빠지는 동작은 보장하지 않는다.
- 다른 멤버가 이미 삭제했다면 DELETE는 그대로 200이다. 삭제 후 재콕된 장소는 새 ID이므로
  과거 ID의 지연 요청·재시도로 새 콕을 삭제하지 않는다.
- 토스트 시간과 화면 이탈·앱 종료 시 보류 요청 처리는 앱 책임이다. 현재 번치는
  요청 시점에 서버가 선택하므로 번치 전환을 넘어 보류 요청을 무조건 보내면 안 된다.
  전환 전에 삭제를 확정하거나 보류를 취소하는 처리가 필요하다.

이 경계는 8.1.8의 서버 즉시 삭제 후 원래 기록 복구를 대체한다.
전체 목록·멤버 필터에도 별도 복구 endpoint는 필요하지 않다.

## 저장·동시성

V28의 pinned_places는 `(bunch_id, provider, external_id)` UNIQUE다.
동일 장소의 다른 번들·광고·멤버 요청도 최초 출처·이름·주소·등록자·시각을 변경하지 않는다.
취소 후 다시 콕하면 새 ID와 새 출처를 기록한다. 알림을 생성하지 않는다.
번치 삭제는 해당 번치의 핀을 CASCADE 삭제한다.

쓰기는 개인 계정 → 관련 번치 ID 오름차순 → 원본 번들 순으로 잠근다.
대기 후 현재 ACTIVE 소속과 원본 접근을 재검사하며 번들 편집과 핀 삽입을 직렬화한다.
번들 장소 삭제 이벤트는 동기 @EventListener + MANDATORY 트랜잭션에서
정확히 `(sourceBundleId, sourceLocationId)`가 일치하는 핀만 삭제한다.
실패하면 장소 편집과 핀 삭제를 함께 롤백한다. 같은 장소의 다른 출처 핀은 유지한다.
이 listener는 저장 대상 번치 잠금을 추가로 획득하지 않는다.
번들 자체 삭제에는 이벤트가 없고 bundle/location FK의 ON DELETE SET NULL로
출처 참조만 비우며 이름·주소·키·등록자·등록시각 스냅샷을 보존한다.

## H/B 내부 계약

`KokService.pinAdvertisement(userId, advertisementId, place: PlaceDetails)`는
MANDATORY 트랜잭션 API다. HTTP로 노출하지 않는다.
H는 실제 광고 DB 행을 잠그고 활성 여부·회원 숨김·접근을 검증한 후 호출해야 한다.
`place`는 H가 읽은 실제 광고의 정규화한 장소다. 임의 앱 입력을 전달하면 안 된다.
G는 현재 ACTIVE 번치 멤버십과 ID를 검증하고 중복 보존을 수행한다.
H의 쓰기는 `members.findLockedById(userId)` → 서버 현재 번치 `findLocked` → 실제 광고 행
순으로 잠그고 검증한 뒤 호출한다. G의 개인/번치 잠금은 같은 트랜잭션에서 재진입한다.
광고 행부터 잠근 뒤 개인/번치 잠금을 새로 획득하지 않도록 H의 숨김·변경·삭제 경로도 정렬한다.
광고 테이블·가짜 adapter는 만들지 않는다. sourceAdvertisementId는 nullable 논리 참조이며
H가 실제 광고 테이블과 참조 제약/삭제 정책을 연결한다.

`findForPlaces(bunchId, places: Collection<PlaceDetails>): List<KokResponse>`는
최대 500개 장소의 현재 번치 핀을 한 번의 쿼리로 조회하는 내부 조립 API다.
호출자가 현재 번치·장소 노출 권한을 검사해야 한다. provider+externalId로 응답을 매칭한다.

## 검증

실제 PostgreSQL/Redis에서 중복·동시 콕·타 멤버 취소·재콕·현재 번치 전환·소속 0개,
숨김/차단/신고/공개범위 재검사·잘못된 장소 소속·HTTP 입력 위조 방지,
실제 replaceLocked의 선택 삭제/롤백·번들/번치 삭제 스냅샷·V28 재실행·제약을 검증한다.
목록은 전체/멤버 필터·0개 멤버·이탈/탈퇴/등록자 null 전체 집계·역할 무관 접근·동일 시각 ID 경계·
삭제된 커서 행·문맥 불일치·현재 번치 전환·원본 비공개/팔로우/차단/숨김/삭제/안내 만료 복구·
출처 이름 최신값·광고 구분·프로필이 있는 여러 출처의 쿼리 수 고정·V2026111111270 적용/재실행을 검증한다.
실제 광고 DB 연계는 H, 전체 발행본 일괄 편집은 C 통합 후 검증한다.
실제 iOS/WebView 수동 검증은 앱 환경 준비 후 진행한다.
