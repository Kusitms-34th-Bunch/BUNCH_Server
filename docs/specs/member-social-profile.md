# 참여 멤버 프로필

이슈 #94, FEED-11, 승인된 6장 시트 6.3.3과 2026-11-07 보완 정책을 따른다.
공통 접근 계약은 [feed-api](feed-api.md), 팔로우 변경은 기존
[번치 소셜 계약](bunch-social.md)을 재사용한다.

## 조회 API

`GET /api/v1/members/{memberId}/social-profile`

기존 JWT·Redis 세션 인증과 ACTIVE·가입 완료 검증을 요구한다. 현재 번치는
`FeedAccessService.viewer(userId)`의 서버 선택값이며 요청 파라미터로 바꾸지 않는다.
조회자의 소속이 0개여도 허용한다. 대상은 ACTIVE·가입 완료 회원이어야 한다.

공통 `success/data/error` 안의 `data`:

| 필드 | 의미 |
| --- | --- |
| `memberId`, `nickname`, `imageUrl` | 대상의 공개 정보. 사진이 없으면 null |
| `currentBunchId` | 조회자의 현재 번치 ID. 소속 0개는 null |
| `bunches` | 대상의 현재 ACTIVE 소속만 포함하는 전체 목록. 없으면 빈 배열 |
| `bunches[].bunch` | 기존 `BunchSocialCard`: id/name/imageUrl/characterType/visibility |
| `bunches[].followState` | 기존 NONE/REQUESTED/FOLLOWING |
| `bunches[].followId` | 기존 팔로우 취소에 사용할 ID. 관계가 없으면 null |
| `bunches[].showFollowButton` | 현재 번치가 있고 해당 행이 현재 번치가 아닐 때 true |

비공개 번치도 팔로우 승인 없이 위 기본정보를 반환한다. `visibility: PRIVATE`로
자물쇠를 표시한다. 멤버 명단·번들·개인 출생연도·성별·동의·외부 식별자·사진 publicId는 반환하지 않는다.
현재 번치 행과 소속 0개 조회는 NONE/null 상태이며 팔로우 버튼을 표시하지 않는다.

이름을 첫 글자부터 비교하며 한글 → 영문(대소문자 무시) → 숫자(연속 숫자 수치 비교)
순서를 적용한다. 그 외 문자는 뒤에서 코드 포인트로 비교한다. 한글은 NFC로 정규화한다.
접두사가 같으면 짧은 이름이 먼저이고, 이름 비교가 같으면 번치 ID 오름차순이다.
예: 여행가조 → 퇴근런클럽 → BookClub → 3반 → 10반.

`FeedAccessRepository.VISIBLE_BUNCH`를 조회에 재사용하여 현재 번치와의 양방향 차단,
기존 프로필 신고, 번치 운영/신고 숨김을 제외한다. 비공개 번치의 승인 여부나 피드의
전체 ACTIVE 소속 제외 규칙은 이 기본정보 목록에 적용하지 않는다.
목록·관계는 일괄 조회하고 사진을 함께 로딩한다. 읽기 전용 REPEATABLE_READ 트랜잭션으로
요청 안의 현재 번치·대상·소속·관계를 일관되게 읽으며 다음 요청에서 다시 확인한다.

## 기존 행동 API와 이동

- 팔로우: `PUT /api/v1/bunches/{currentBunchId}/following/{targetBunchId}`.
  기존 `BunchSocialService`가 공개 즉시 승인·비공개 요청과 멱등성을 처리한다.
- 취소: `DELETE /api/v1/bunches/{currentBunchId}/following/{targetBunchId}?followId={followId}`.
  기존 취소 시트와 반환 상태를 사용한다. 프로필 조회 자체는 상태를 변경하지 않는다.
- 행 선택은 기존 번치 프로필로 이동하며, 현재 번치 행은 번치 메인으로 이동한다.
- 발행 참여자 목록의 나간/탈퇴 참여자는 작성자·기본 이미지로 표시하고 링크를 주지 않는다.
  발행 스냅샷에서의 참여 자격 판정은 #88 소유다. 이 API는 과거 참여 이력을 받지 않으며
  특정 번치를 나갔다는 이유로 여전히 활성인 개인 계정 전체를 비활성으로 취급하지 않는다.
  대상이 소속 0개면 빈 목록이고, 재참여하면 다시 ACTIVE 소속에 포함된다.

## 실패와 검증

| 조건 | HTTP / 코드 |
| --- | --- |
| 인증 없음·만료·세션 해제·조회자 비활성 | 401 / 기존 인증 코드 |
| 조회자 가입 중 | 403 / AUTH_015 |
| 대상 없음·탈퇴·정지·가입 중 | 404 / USER_001 (상태 구분 없음) |
| 대상 ID가 양수가 아님 | 400 / COMMON_001 |

기존 `/members/me` 계약은 그대로다. 새 의존성·마이그레이션·운영 API를 추가하지 않는다.
실제 PostgreSQL·Redis 통합 테스트로 권한·소속 0개·현재 전환·정렬·팔로우/취소·차단 양방향·
신고/운영 숨김·탈퇴/재참여·중복 및 동시 조회·개인정보 제외·JWT 세션을 검증한다.
최종 JDK 21 `clean build`를 수행한다. 실제 iOS/WebView 화면·시트와 APNs는 별도 수동 검증이다.
