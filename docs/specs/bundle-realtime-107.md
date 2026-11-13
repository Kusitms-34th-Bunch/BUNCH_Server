# #107 비독점 필드 editing·제목/코멘트 typing / 화면 presence

2026-11-12 최신 명세에 따라 TITLE/COMMENT는 실제 TYPE만 표시 근거로 사용한다.
focus만으로 시작하던 편집 표시는 제거하며 PICK 작업은 표시하지 않는다.
DATE·LOCATION·PHOTOS·VISIBILITY·PARTICIPANTS와
PHOTO:{bundlePhotoId}·LOCATION:{bundleLocationId}의 비독점 editing을 BEGIN/END로 표시한다.
하나의 연결에서 사진 업로드와 날짜·위치 작업을 동시에 유지할 수 있다.
사진·위치 이동 중 좌표 전송과 상대 화면 이동 애니메이션은 후속 구현이며 이번 계약에 추가하지 않는다.
editing과 typing은 내용 저장이나 잠금이 아니다.
기존 [번들 API](bundle-api.md)의 인증 CONNECT·topic·사용자 queue·envelope를 재사용한다.
단일 서버의 메모리 상태이며 DB 저장·이벤트 재생·Redis Pub/Sub는 없다.
내용 저장은 presence 상태로 차단하지 않으며 현재 인증·참여 권한과 DB 행 잠금으로 검증한다.
2026-11-14 결정으로 구형 편집권 명령·토큰·전용 큐는 제거한다. 호환 경로는 제공하지 않는다.

## 인증과 명령

CONNECT의 access token과 활성 인증 세션으로 연결을 식별하며 매 SEND/구독/전달 시
기존 인증·번치 멤버십·번들 접근 검증을 적용한다. 본문 memberId는 신뢰하지 않는다.
초안 참여자만 BEGIN과 TYPE를 보낼 수 있다. COMMENT는 본인 칸이며
VIEW는 초안 열람 가능한 멤버가 가능하다.
발행본에는 새 presence를 만들 수 없다.

SEND `/app/bunches/{bunchId}/bundles/{bundleId}/presence`:

| action | 입력 | 효과 |
| --- | --- | --- |
| VIEW | action만 | 화면 입장 및 heartbeat. 5초마다 전송, 마지막 신호부터 15초 유효 |
| BEGIN | target, text 없음 | 해당 연결의 지정 target editing만 시작/갱신. 다른 target은 유지. target별 마지막 BEGIN부터 15초 유효. TITLE/COMMENT는 호환용으로 수신하되 표시를 만들지 않음 |
| END | 선택 target, text 없음 | 해당 연결의 지정 target editing·typing 즉시 제거. target 생략 시 해당 연결의 모든 editing·typing 제거. 화면 입장은 유지 |
| TYPE | target: TITLE 또는 COMMENT, text: 실제 현재 입력 문자열 | 비독점 typing 시작/갱신. 빈 문자열도 삭제 입력이다 |
| STOP / BLUR | 선택 target, text 없음 | 지정 target editing·typing만 즉시 제거. 생략하면 해당 연결의 모든 editing·typing 제거. 화면 입장은 유지 |
| LEAVE / BACKGROUND | action만 | 해당 연결의 화면 입장·editing·typing 즉시 제거 |

BEGIN/END/STOP/BLUR의 단순 target은 `TITLE`, `COMMENT`, `DATE`, `LOCATION`, `PHOTOS`,
`VISIBILITY`, `PARTICIPANTS`이고 항목 target은 `PHOTO:{bundlePhotoId}`,
`LOCATION:{bundleLocationId}`다. TITLE/COMMENT BEGIN은 호환용으로 수신해 화면 입장 신호만
갱신하며 editing을 만들거나 기존 typing을 지우지 않는다. `PICK:{bundlePhotoId}` BEGIN은
`INVALID_INPUT`으로 거부한다. 이전 클라이언트의 PICK END/STOP/BLUR는 문법만 검증하고
종료할 수 있지만 PICK 상태를 만들지 않는다.
PHOTOS는 사진 작업 그룹이며 PHOTO의 ID는 원본 Photo ID가 아니라
양의 10진 정수 `bundle_photos.id`다. BEGIN 시 서버가 대상 초안의 현재 사진 행인지 검증하며,
잘못된 형식·다른 번들·없는 사진 행을 새 editing으로 등록하지 않는다.
END/STOP/BLUR는 target 문법만 검증하고 행 존재를 조회하지 않아 이미 삭제된 항목도 정리할 수 있다.
잘못된 target이나 종료 명령의 text는 INVALID_INPUT으로 거부하며 기존 유효 작업은 변경하지 않는다.
예를 들어 같은 연결에서 PHOTOS → DATE BEGIN 후 TYPE COMMENT를 보내면 사진·날짜 표시와
코멘트 입력이 함께 유지된다. BLUR DATE는 날짜만, STOP COMMENT는 코멘트 입력만 종료한다.
PHOTOS의 마지막 BEGIN 기한은 DATE/TYPE 신호로 갱신되지 않는다.
COMMENT의 작성자는 인증 연결로 정하며 다른 멤버의 칸을 지정할 수 없다.

제목/코멘트 focus는 BEGIN/TYPE를 보내지 않는다. 실제 입력한 경우에만 TYPE를 보낸다.
사진 drag·위치 편집·날짜 선택 등 실제 상호작용은 BEGIN을 보내고, 계속되는 target마다
5초마다 반복한다. 단순 화면 열람은 VIEW만 보낸다. BEGIN은 text를 받지 않으며
TYPE preview를 만들거나 지우지 않는다. TITLE/COMMENT 이외의 target에는 text TYPE preview가 없다.
사진 추가 시트·사진함 선택·갤러리 선택·업로드·삭제 확인 모달·드래그는 클라이언트가
열린 화면/진행 작업의 합집합으로 관리하고, 하나라도 유지되면 PHOTOS BEGIN을 갱신한다.
PHOTOS와 PHOTO 항목의 프로필은 모두 「사진」 제목 옆에 모아서 표시한다.
선택창을 닫아도 업로드가 남아 있으면 PHOTOS를 종료하지 않는다. 갤러리 진입에 따른
단순 window blur와 실제 앱 BACKGROUND를 구분하고, 필드 종료에는 target을 반드시 지정해
다른 업로드나 작업 상태를 지우지 않는다. 실제 앱 이탈은 BACKGROUND로 모든 작업을 종료한다.
날짜 선택 화면이 열린 동안 DATE를 「날짜 추가」 옆에 표시한다.
업로드 진입 시 현재 다른 멤버의 editing/typing을 안내 모달에 사용하되 저장·발행을 잠그지 않는다.

단순 선택/focus는 TYPE를 보내지 않는다. TYPE는 TITLE 전체 입력 또는 본인 COMMENT 전체
입력을 preview로 전달한다. COMMENT는 400 grapheme까지, TITLE은 40자 초과도
유지한다. TITLE은 줄바꿈·NUL을 거부하며, 입력 payload는 UTF-8 256KiB 이하다.
검증 후 preview text는 입력 원문을 보존한다. preview는 저장/CRDT update가 아니므로 내용 저장 API를 별도로 호출한다.
명령은 기존 번치→번들 잠금으로 발행/참여 제거와 직렬화한다.
DB 잠금을 먼저 얻고 상태 변경에만 메모리 monitor를 사용해 커밋 후 정리와 잠금 역전을 피한다.
COMMENT 작성자 memberId는 인증 연결에서만 결정한다. 상대 코멘트를 지정하는 입력은 없다.
연결 중 VIEW heartbeat는 typing의 3초 기한이나 editing의 마지막 BEGIN+15초 기한을 연장하지 않는다.
TYPE도 editing heartbeat를 대신하지 않으며, BEGIN은 typing의 3초 기한을 연장하지 않는다.

## 응답과 새 입장 복구

기존 `/topic/bunches/{bunchId}/bundles/{bundleId}`에 전체 상태를 broadcast하고,
명령을 보낸 연결은 기존 `/user/queue/bundle-activity`에서 같은 상태를 받는다.
기존 `type / bunchId / bundleId / revision / requestId / data` envelope를 유지하며
`type=PRESENCE`, `revision=null`, `requestId=null`이다. 명령 거절도
`/user/queue/bundle-activity`에서 `COMMAND_REJECTED`로 받는다.

인증 REST `GET /api/v1/bunches/{bunchId}/bundles/{bundleId}/presence`는 같은 data를
`success/data/error`로 반환한다. 조회는 화면 입장으로 기록되지 않는다.
구독을 먼저 완료한 뒤 GET으로 상태를 복구하고, 이후 topic 상태로 교체한다.

```json
{
  "members": [{"memberId": 12, "expiresAt": "2026-11-11T12:00:15Z"}],
  "editing": [{
    "memberId": 12,
    "target": "LOCATION",
    "expiresAt": "2026-11-11T12:00:15Z"
  }],
  "typing": [{
    "memberId": 12,
    "target": "COMMENT",
    "text": "지금 입력한 코멘트",
    "expiresAt": "2026-11-11T12:00:03Z"
  }]
}
```

여러 연결은 memberId로 합친다. 하나의 연결이 나가도 다른 연결의 입장은 유지된다.
editing은 연결마다 target별 map으로 관리한다. 같은 target BEGIN은 해당 기한만 갱신하고
다른 target BEGIN은 기존 작업과 함께 유지한다. END/STOP/BLUR(target)은 지정 작업만 종료한다.
인자 없는 END/STOP/BLUR의 전체 종료는 이전 클라이언트 호환 동작이다.
공개 응답에서는 `(memberId, target)`별 유효 상태를 합친다.
같은 사용자·target의 expiresAt은 아직 유효한 연결 중 가장 늦은 만료 시각이다.
한 연결의 END/BLUR/STOP은 다른 연결의 유효 editing을 지우지 않는다. 서로 다른 target과
여러 사용자의 같은 target은 동시에 표시할 수 있으며 독점 획득·충돌 거부는 없다.
같은 사용자/target의 동시 typing은 서버에서 마지막으로 받은 실제 TYPE의 text를 보여준다.
그 연결이 멈추면 아직 유효한 다른 연결의 typing을 보여준다. 여러 연결을 합친 상태에서는
TITLE/COMMENT를 동시에 표시할 수 있다.
editing 항목에는 `memberId/target/expiresAt`만 둔다. 연결 ID·인증 세션·token은 공개하지 않는다.
멤버는 memberId, editing·typing은 memberId/target 순서다.

## 시간과 정리 우선순위

1. 명시적 END/STOP/BLUR/LEAVE/BACKGROUND와 커밋된 권한·상태 변경이 우선한다.
2. 연결 중에는 마지막 TYPE부터 3초에 typing, 해당 연결·target의 마지막 BEGIN부터 15초에 editing을 제거한다.
   VIEW만 계속 와도 BEGIN이 멈춘 editing은 연장되지 않는다.
3. 실제 disconnect가 관찰되면 아직 유효한 typing은 마지막 유효 presence 신호부터
   15초까지 유예한다. disconnect 시각부터 새로 15초를 세지 않는다. 이미 해제된
   typing을 부활시키지 않는다. 입장도 같은 마지막 신호+15초에 만료된다.
   아직 유효한 editing은 `min(마지막 BEGIN, 해당 연결 마지막 유효 신호) + 15초`에 만료된다.
   disconnect 후에도 VIEW가 마지막 BEGIN+15초 기한을 연장하지 않는다. disconnect 시각부터
   새로 15초를 세거나 이미 END/만료 등으로 끝난 editing을 부활시키지 않는다.
   예: BEGIN 0초 → VIEW 10초 → disconnect 10초이면 editing은 15초, 입장은 25초에 만료된다.
4. 1초 주기 정리와 모든 조회/명령에서 만료를 계산한다. 응답 expiresAt 이후 표시는
   클라이언트도 제거하여 정리 스케줄 지연이 15초 표시 상한을 늘리지 않게 한다.
   연결 자체가 아직 감지되지 않은 무신호 상황도 입장은 마지막 신호+15초를 넘지 않는다.

발행·삭제는 BundleChangedEvent의 PUBLISHED/DELETED를 AFTER_COMMIT에서 처리해 모두
제거한다. PARTICIPANTS/PERMISSIONS는 커밋 후 새 읽기 트랜잭션에서 접근·참여를 재검사한다.
접근 상실·참여 제거는 해당 사용자의 모든 연결 상태를 제거한다.
사진 행 삭제 시 해당 PHOTO editing만 정리하며 동시에 진행 중인 PHOTOS 업로드·DATE 등은 유지한다.
장소 행 삭제도 해당 LOCATION:{id}만 정리한다.
rollback은 presence에 영향을 주지 않는다.
일시적인 presence/editing/typing은 번들 revision/updatedAt·번치 활동·알림을 바꾸지 않는다.

재접속은 새 연결이다. 최신 내용 snapshot과 GET presence를 다시 읽고 서버 미반영 입력은
폐기한다. 오프라인 큐·preview 재전송·편집권 잠금은 추가하지 않는다.

## 검증 기준

검증 대상: 연결 중 TYPE 3초·BEGIN 15초, disconnect 후 typing 마지막 신호+15초와
editing `min(마지막 BEGIN, 마지막 연결 신호)+15초` 경계·타이머 우선순위,
VIEW/BEGIN/TYPE 기한 분리와 target별 독립 갱신, TITLE/COMMENT focus BEGIN 무표시,
END/STOP/BLUR의 target 지정/생략·삭제 항목 문법 검증, PHOTO BEGIN 행 검증·PHOTOS 그룹,
PICK BEGIN 거부, 업로드와 DATE/LOCATION/실제 TYPE의 동시 유지, 인증 작성자,
여러 연결/여러 작성자, 즉시 end/blur/stop/leave/background, 발행/삭제/접근 상실과 rollback.
실제 STOMP 검증 대상: 인증 명령·topic/queue envelope·editing 배열·실제 text·REST 새 상태·권한 거부.
이 기준은 요구 계약이며 BEGIN/END 구현이나 검증 완료를 뜻하지 않는다.
실제 iOS/WebView 두 명 입력·background/재접속 검증은 자동 테스트와 별도다.

## 2026-11-12 사용자 확정: 장소 항목별 작업 표시

기존 장소의 수정·교체·삭제·이동은 `BEGIN target=LOCATION:{bundleLocationId}`로 구분한다.
ID는 bundle_locations.id이며 provider/externalId나 목록 index가 아니다. BEGIN 시 현재
번들에 존재하는 행인지 검증한다. 다른 번들/없는 행은 거부하며 기존 유효 신호는 유지한다.
새 장소를 추가하는 폼의 작업은 아직 ID가 없으므로 기존 `LOCATION` 그룹을 사용한다.
다른 멤버는 editing의 memberId와 target을 각 장소 행에 매핑해 표시한다. 같은 장소를
여러 명이 편집해도 잠그지 않으며 여러 멤버를 모두 표시한다. END와 만료/권한 정리는 기존과 같다.
LOCATION 변경 커밋 후 사라진 장소의 작업 표시만 정리한다. 삭제된 항목의 END는
행 존재 검사를 하지 않아 종료할 수 있다. 내용 revision/활동/알림을 변경하지 않는다.
