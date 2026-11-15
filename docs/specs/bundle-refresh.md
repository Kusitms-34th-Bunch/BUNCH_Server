# 번들 권한·추천 날짜 칩 갱신 (#107)

열린 초안 화면의 파생 상태를 기존 번들 STOMP topic으로 알린다. 새 전송 경로·구독·이벤트 저장소는 만들지 않는다.
위치 쓰기는 [번들 위치](bundle-locations.md), 공통 envelope·재접속은 [번들 API](bundle-api.md)를 따른다.

## 원본 변경과 대상

| 원본 변경 | 갱신 대상 | type / data |
| --- | --- | --- |
| BunchMembershipManagementService.changeRole의 실제 역할 변경 | 해당 번치의 모든 DRAFT | PERMISSIONS / null |
| BunchMembershipManagementService.transferOwner의 실제 관리자 위임 | 해당 번치의 모든 DRAFT | PERMISSIONS / null |
| 날짜가 있는 PhotoChangedEvent. 실제 PhotoService.changeDate의 날짜 설정·변경·해제 포함 | 해당 번치의 date=null인 DRAFT | DATE_CHIPS / {dateChips: [...]} |

PUBLISHED와 다른 번치의 번들에는 전달하지 않는다. 날짜가 지정된 초안은 DATE_CHIPS 대상에서 제외하며
PERMISSIONS 대상에는 포함한다. PhotoChangedEvent의 capturedDate=null은 갱신하지 않는다.
날짜 변경·해제는 사진 서비스가 이전 날짜와 새 날짜를 알리는 기존 이벤트를 그대로 사용한다.
배치나 날짜 변경 하나가 여러 원본 이벤트를 만들면 각 이벤트가 갱신을 알릴 수 있다.
이 신호를 하나의 내용 변경으로 세거나 클라이언트가 중복 입력을 재생하는 근거로 사용하지 않는다.

역할이 이미 같아 변경되지 않은 요청과 관리자 위임의 멱등 재시도는 새 PERMISSIONS를 발행하지 않는다.
BunchMembershipChangedEvent는 bunchId만 포함하며 개인별 권한이나 다른 사용자의 토큰을 전달하지 않는다.
참여 종료·내보내짐은 기존 해당 도메인의 권한 검증·참여자 변경 경로를 유지한다.

## 트랜잭션·전달

BundleRefreshEventListener는 동기 @EventListener와 MANDATORY 트랜잭션으로 원본 변경에 참여한다.
이곳에서 기존 BundleChangedEvent를 만들고 BundleRealtimeEventListener가 AFTER_COMMIT에
`/topic/bunches/{bunchId}/bundles/{bundleId}`로 전달한다. 원본 트랜잭션이 끝나기 전에는 전달하지 않으며,
원본 변경이나 후속 처리가 롤백되면 파생 신호도 전달하지 않는다.

공통 envelope의 revision은 현재 저장된 번들의 revision이며 requestId는 서버가 생성한 추적용 UUID다.
이 갱신 자체는 revision·필드 version·updatedAt·멤버 활동 시각을 변경하지 않는다.
위치나 사진 순서 변경 같은 실제 내용 변경의 버전·이벤트와 구분한다.

DATE_CHIPS는 BundleService.dateChips의 기존 계산을 재사용한다. 날짜 미지정 초안에서 추천 출처 날짜를
우선하고, 연결된 원본 사진의 최신 날짜를 사진 수 내림차순·날짜 오름차순으로 계산하여 최대 3개를 보낸다.
미래 날짜는 제외하며 추천 계산 자체나 사진함 정책을 이 listener에서 새로 구현하지 않는다.
PhotoService의 날짜 변경은 기존 일괄 갱신 뒤 영속성 문맥을 정리하므로 새 날짜로 칩을 조회한다.

PERMISSIONS 수신자는 각자의 인증으로 최신 GET snapshot을 다시 읽어 권한과 입장 가능 여부를 확인한다.
초안 participants에는 현재 프로필(nickname/imageUrl/profileAvailable), 각자의 role과 creator가
별도로 포함된다. OWNER/CO_OWNER/MEMBER 역할과 초안 생성자 여부를 합쳐 단일 배지 값으로
만들지 않으며, 앱이 우선순위를 적용한다. 재조회로 역할 변경/관리자 위임을 반영한다.
서버는 다음 쓰기·상태 전송에서도 권한을 검증하며 신호 수신 여부만으로 권한을 허용하지 않는다.
재연결에서도 기존 GET snapshot을 기준으로 복구한다. 연결 중 유실의 재생·오프라인 큐·범용 Pub/Sub는 추가하지 않는다.
브로커 전송 실패 처리와 기존 구독·인증 경계는 공통 realtime 구현을 따른다.

## 자동·수동 검증 경계

BundleRefreshIntegrationTest는 실제 역할 변경·관리자 위임·PhotoService.changeDate를 호출하고
실제 BundleRealtimeEventListener가 전달하는 SimpMessagingTemplate 호출을 관찰한다.
커밋 전 미전달·커밋 후 기존 topic 전달·롤백 미전달, no-op/멱등 재시도,
미지정/지정 날짜·발행 상태·번치별 필터, 최신 칩 payload와 내용/활동 시각 불변을 검증한다.
실제 앱 2인 세션의 권한 재조회·화면 이동·날짜 칩 반영과 네트워크 재연결은 별도 수동 검증 대상이다.
테스트 실행 결과는 작업 보고에 남기며 이 계약 문서에서 실행 성공을 가정하지 않는다.
