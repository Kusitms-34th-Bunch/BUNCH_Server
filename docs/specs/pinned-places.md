# 장소 콕하기 계약

#92, FEED-10, 승인한 6.3.1 및 연계 8.1.3을 구현한다.
[피드 접근](feed-api.md)과 [번들 장소](bundle-locations.md) 계약을 따른다.
전체 8장 화면·목록·필터는 #125, 검색 공급자와 주소 없는 지역의 계약 정렬은
[#126 장소 검색 계약](place-search.md)에서 추적한다.
8.1.8의 되돌리기는 2026-11-08 승인으로 앱의 삭제 요청 지연 방식으로 처리한다.

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
전체 목록·멤버 필터를 추가하는 후속 서버 작업에도 별도 복구 endpoint는 필요하지 않다.

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

`PinnedPlaceService.pinAdvertisement(userId, advertisementId, place: PlaceDetails)`는
MANDATORY 트랜잭션 API다. HTTP로 노출하지 않는다.
H는 실제 광고 DB 행을 잠그고 활성 여부·회원 숨김·접근을 검증한 후 호출해야 한다.
`place`는 H가 읽은 실제 광고의 정규화한 장소다. 임의 앱 입력을 전달하면 안 된다.
G는 현재 ACTIVE 번치 멤버십과 ID를 검증하고 중복 보존을 수행한다.
H의 쓰기는 `members.findLockedById(userId)` → 서버 현재 번치 `findLocked` → 실제 광고 행
순으로 잠그고 검증한 뒤 호출한다. G의 개인/번치 잠금은 같은 트랜잭션에서 재진입한다.
광고 행부터 잠근 뒤 개인/번치 잠금을 새로 획득하지 않도록 H의 숨김·변경·삭제 경로도 정렬한다.
광고 테이블·가짜 adapter는 만들지 않는다. sourceAdvertisementId는 nullable 논리 참조이며
H가 실제 광고 테이블과 참조 제약/삭제 정책을 연결한다.

`findForPlaces(bunchId, places: Collection<PlaceDetails>): List<PinnedPlaceResponse>`는
최대 500개 장소의 현재 번치 핀을 한 번의 쿼리로 조회하는 내부 조립 API다.
호출자가 현재 번치·장소 노출 권한을 검사해야 한다. provider+externalId로 응답을 매칭한다.

## 검증

실제 PostgreSQL/Redis에서 중복·동시 콕·타 멤버 취소·재콕·현재 번치 전환·소속 0개,
숨김/차단/신고/공개범위 재검사·잘못된 장소 소속·HTTP 입력 위조 방지,
실제 replaceLocked의 선택 삭제/롤백·번들/번치 삭제 스냅샷·V28 재실행·제약을 검증한다.
실제 광고 DB 연계는 H, 전체 발행본 일괄 편집은 C 통합 후 검증한다.
실제 iOS/WebView 수동 검증은 앱 환경 준비 후 진행한다.
