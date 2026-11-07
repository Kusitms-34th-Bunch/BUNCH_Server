# 피드 광고 계약

#93, FEED-09·FEED-10, 승인된 시트 6.1.5~6을 구현한다.
[피드 접근](feed-api.md), [장소 콕](pinned-places.md)을 재사용한다.
GET 피드 페이지·6번들 간격·커서 계산은 #95가 소유한다.

## 노출과 내부 조립 API

`AdvertisementService.selectForFeed(userId, bundleCount, slots, lastAdId)`에서
bundleCount는 이번 페이지 번들 수(0..50), slots는 광고를 붙일 번들 뒤의
1-based 위치 목록(오름차순·중복 없음), lastAdId는 이전 실제 노출 광고 ID다.
B는 페이지·FOLLOWING/PUBLIC 경계를 넘는 누적 6번들 간격으로 slots를 계산한다.
반환 타입은 `com.bunch.feed.dto.AdvertisementBatchResponse`다.
`placements: List<AdvertisementPlacement>`와 `lastAdId: Long?`를 가진다.
각 `AdvertisementPlacement`는 `afterBundle: Int`와
`advertisement: AdvertisementResponse`다. 모든 DTO는 같은 feed.dto 패키지다.
광고가 없는 응답도 nullable batch가 아닌 빈 placements를 반환한다.
생략한 슬롯은 placements에 없으며 lastAdId는 마지막 실제 노출 ID를 유지한다.
광고는 번들 page size에 포함하지 않고 bundleCount=0이면 광고를 반환하지 않는다.

매 호출 DB에서 활성 광고 중 회원 개인 숨김을 제외한 후보를 새로 읽는다.
응답 안에서 아직 뽑지 않은 후보 중 직전 광고를 제외하고 균등 선택한다.
후보를 모두 쓰면 새 풀을 시작한다. 직전 ID만 남아 대안이 없으면 슬롯을 생략한다.
결과 캐시·고정 시퀀스가 없으므로 동일 페이지 재요청의 광고는 달라도 된다.
랜덤 선택은 후보 인덱스의 균등 난수이며 테스트는 제어한 난수로 검증한다.
선택한 장소들의 현재 번치 핀 상태는 G의 findForPlaces 배치 API로 조회한다.

광고 응답: `{id, name, introduction, photoUrl, tags, externalLink, place,
pinned, pinId, canPin}`, place는 `{provider, externalId, name, address}`다.
외부 링크는 null이면 동작 없음. 소속 0개는 조회 가능하며 canPin=false다.
현재 ACTIVE 계정·가입 완료를 매 요청 검사한다.

## 앱 API

공통 JWT 인증과 `success / data / error`를 사용한다.

- `GET /api/v1/feed/advertisements/{advertisementId}`: 현재 노출 가능한 단일 광고.
- `PUT /api/v1/feed/advertisements/{advertisementId}/hide`: 본문 없이 개인 영구 숨김.
  회원→광고 행 순서로 잠그며 번치 잠금은 필요 없다.
  해제 API가 없고 중복·동시 호출도 200/data=null이다. 비활성 광고도 숨길 수 있다.
  같은 번치의 다른 멤버와 번치 전환에 영향을 주지 않는다.
- `PUT /api/v1/feed/advertisements/{advertisementId}/pin`: 본문 없이 실제 광고 장소를 콕.
  H가 회원→서버 현재 번치→실제 광고 행 순서로 잠근다.
  번치 잠금 뒤 refresh·현재 ACTIVE 소속을 재검사하고 광고 활성·개인 숨김을 검증한 뒤
  G의 실제 pinAdvertisement를 호출한다. G는 회원·번치 잠금에 재진입한다.
  임의 요청 장소 payload는 사용하지 않는다. 응답은 G의 PinnedPlaceResponse다.
- 콕 취소는 기존 `DELETE /api/v1/pinned-places/{pinId}`를 사용한다.

잘못된 ID·슬롯은 COMMON_001/400, 없는 광고·비활성/숨긴 광고 조회 및 콕은
RESOURCE_001/404, 미가입 AUTH_015/403, 비활성 계정 AUTH_001/401,
소속 0개 콕은 BUNCH_010/409다. 숨김은 소속 0개도 가능하다.

## DB 관리

V29에서 광고와 개인 숨김을 만든다. 운영자 API·새 의존성은 추가하지 않는다.
장소는 공용 PlaceDetails.of와 같은 provider·외부 ID·이름·주소 계약이다.
광고를 하드 삭제하면 개인 숨김은 CASCADE, 기존 핀의 광고 ID는 논리 참조로 남기고
장소 스냅샷은 보존한다. 평상시 운영은 active=false로 중단한다.
ID를 재사용하지 않는다. 같은 광고 내용 수정은 개인 숨김을 유지한다.

```sql
BEGIN;
INSERT INTO feed_advertisements
    (name, introduction, photo_url, tags, external_link,
     place_provider, place_external_id, place_name, place_address, active)
VALUES ('서울숲 공방', '함께 만드는 도자기', 'https://example.com/studio.jpg',
        '["최대 4인", "예약 가능"]'::jsonb, 'https://example.com/reserve',
        'kakao', 'studio-001', '서울숲 공방', '서울 성동구 예시로 1', true)
RETURNING id;
COMMIT;

-- 실제 ID를 바인딩한다. 신규 광고를 여러 행 추가해 풀을 운영한다.
UPDATE feed_advertisements
SET introduction = '새 소개', tags = '["예약 가능"]'::jsonb,
    external_link = NULL, updated_at = now()
WHERE id = :advertisement_id;

UPDATE feed_advertisements SET active = false, updated_at = now()
WHERE id = :advertisement_id;
```

provider는 trim/lowercase ASCII 1..32, 외부 ID는 trim 1..128 code point,
이름은 trim 1..200 grapheme, 주소는 trim 1..500 grapheme이다.
사진과 선택적 외부 링크는 http(s) 절대 URL이다. 태그는 JSON 문자열 배열이다.
운영 SQL 입력도 공용 길이 정책을 지킨다. DB는 기본 형식·빈 값·참조를 제약한다.

## 검증 범위

순수 선택 테스트는 후보 범위·소진·직전 제외·생략을 확률 없이 검증한다.
실제 PostgreSQL/Redis 테스트는 DB 갱신 즉시 반영·개인 숨김 영속/동시 멱등·
현재 번치 핀 배치·소속 0개·서버 장소 사용·비활성/숨김 거부·V29를 검증한다.
전체 GET 피드 간격·커서 통합은 B 후속이며 iOS/WebView 수동 확인은 별도다.
