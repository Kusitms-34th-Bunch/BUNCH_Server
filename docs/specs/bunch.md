# 번치 Spec 색인

번치 탭·관리·다른 번치 조회와 알림의 서버 계약을 기능별 문서로 연결한다.
정책 원문은 [번치 5장 시트](https://docs.google.com/spreadsheets/d/1ScziF7WoYLHRiC_UYJBkKsi0jL6KKQJD2TNDzW1QGfU/edit?gid=2104162394#gid=2104162394)다.
2026-10-31 사용자 보완 답변을 반영하며 원문의 기능 ID와 47개 항목을 유지한다.
구현·테스트·관련 계약은 같은 기능 PR에 포함한다. 이 색인과 요구사항 확정만으로
서버 구현·실제 iOS 화면/푸시·출시 완료를 표시하지 않는다.

## 확정 정책

- 메인 `OWNER` 1명, 공동 `CO_OWNER`, 일반 `MEMBER`를 구분한다. 프로필·번치 삭제·
  역할 지정/해제·메인 인계는 OWNER만 가능하다. CO_OWNER는 MEMBER만 내보낸다.
- 발행본 공용 변경·삭제는 OWNER만, 본인 코멘트는 작성자만 수정한다.
  초안 삭제는 생성자 또는 참여 중인 관리자만 가능하며 생성자 이탈 시 초안은 삭제한다.
- 뭉치는 현재 전체 PUBLIC/PRIVATE 발행 수로 계산하며 0/2/6/10개에서 1~4단계다.
  외부에도 같은 실제 단계를 표시하되 비공개 번들 수·내용은 노출하지 않는다.
- 메인 관리자의 직접 나가기는 후임 선택이 필수다. 계정 탈퇴의 자동 승계는 #29 범위다.
- LEFT·REMOVED 모두 유효한 초대와 새 요청 키로 새 MEMBER 소속에 참여할 수 있다.
  남은 발행본의 동일 계정 이름·프로필·코멘트 표시만 복구한다. 삭제된 사진함·업로더·
  초안·초안 코멘트·편집권·예전 관리자 권한은 복구하지 않는다. 삭제한 번치는 복구하지 않는다.
- 프로필 편집은 이미지·이름·소개·공개 범위의 일괄 저장이다. 생성 완료 이미지 즉시 저장과
  구분한다. 앱 원본 JPEG/PNG/HEIC/WebP 10MB와 서버 전송 JPEG 5MiB/1024px 계약은 별개다.
- 접근 안내는 본체 FK 없이 보관하고 확인/30일 후 제거한다. 직접 나가기·직접 삭제에는
  본인 안내를 만들지 않는다. 내보내짐·번치 삭제는 알림 inbox 없이 푸시만 보낸다.
- 일반 앱 내 알림은 30일·최대 100개 표시, 미확인 수는 보관 전체 기준이다.
  리마인드는 각 참여자 참여 후 24/72시간에 최대 두 번이다. 설정은 푸시에만 적용한다.
- 업무·알림·현재 기기의 전송 대기는 같은 DB 트랜잭션에 저장한다. 전송 실패는 업무 성공을
  되돌리지 않으며 APNs 접수를 실제 기기 표시·중복 없는 최종 전달로 간주하지 않는다.

## 기능별 계약

| 기능 | 계약·검증 소유 |
| --- | --- |
| 역할·권한·Stage | [역할·Stage](bunch-role-stage.md), [번들](bundle.md), [번들 API](bundle-api.md) |
| 생성·초대·참여 | [온보딩](onboarding.md), [가입·온보딩 API](login-onboarding-api.md) |
| 알림 목록·읽음·독촉 | [알림](notification.md), [API](notification-api.md), [저장](notification-schema.md) |
| 소셜·외부 프로필 | [소셜·외부 프로필](bunch-social.md) |
| 기기·설정·APNs | [푸시](push.md) |
| 역할 관리·메인 인계 | [역할 관리](bunch-role-stage.md) |
| 이탈·사진 정리·재참여 | [이탈·재참여](bunch-membership-exit.md), [사진함 기반](photo-foundation.md) |
| 번치 삭제 | [삭제](bunch-deletion.md) |
| 홈·전환·프로필 일괄 저장 | [홈·프로필](bunch-home-profile.md) |

## 원문 추적 — 47개

각 항목의 서버 책임은 연결된 기능 계약에서 검증한다. 아이콘·3열 배치·탭·모달·입력/사진 선택·
실제 iOS 푸시 수신/복귀는 앱 책임이며 서버 자동 테스트로 수동 검증 완료를 대신하지 않는다.

| 원문 | 기능 ID | 내용 | 서버 구현 소유 |
| --- | --- | --- | --- |
| 5.1.1 | BUNCH-02, BUNCH-03 | 번치 메인 화면 구성 | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.1.2 | BUNCH-04, NOTI-01, BUNCH-07 | 헤더(번치 전환·알림·더보기) | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.1.3 | PROFILE-01, PROFILE-02 | 뭉치 영역 | [#47](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/47), [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.1.4 | BUNCH-02, FEED-03 | 번치 프로필 영역 | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.1.5 | BUNCH-03, DRAFT-11 | 번들 탭 영역 | 기존 번들 API, [#47](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/47), [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.1.6 | DRAFT-01 | 번들 만들기 버튼(+) | 기존 번들 API, [#47](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/47), [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.1.7 | BUNCH-06 | 번치가 없을 때 번치 탭 | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.2.1 | BUNCH-04 | 번치 전환 시트 | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.2.2 | BUNCH-04, BUNCH-05 | 다른 번치로 전환 | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.2.3 | BUNCH-04 | 삭제된·내보내진 번치 안내 | [#25](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/25), [#50](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/50), [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.2.4 | BUNCH-06 | 새로운 번치 추가하기 | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.3.1 | DRAFT-11 | 작성 중인 번들 탭 | [#47](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/47) |
| 5.3.2 | DRAFT-13 | 작성 중인 번들 삭제 | [#47](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/47) |
| 5.4.1 | BUNCH-07 | 더보기 메뉴(역할별 메뉴) | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.5.1 | BUNCH-08 | 번치 프로필 편집 화면 구성 | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.5.2 | BUNCH-08 | 번치 프로필 이미지 변경 | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.5.3 | BUNCH-08 | 번치 이름·한 줄 소개 수정 | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.5.4 | BUNCH-08, FEED-06 | 번치 공개 범위 변경 | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.5.5 | BUNCH-08 | 프로필 저장하기 | [#52](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/52) |
| 5.6.1 | MEMBER-07 | 멤버 관리 화면 구성 | [#49](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/49) |
| 5.6.2 | MEMBER-07 | 역할별 권한 안내 화면 | [#49](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/49) |
| 5.6.3 | MEMBER-10 | 공동 관리자로 지정하기 | [#49](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/49) |
| 5.6.4 | MEMBER-10 | 공동 관리자 권한 해제 | [#49](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/49) |
| 5.6.5 | MEMBER-09 | 번치에서 내보내기 | [#25](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/25), [#38](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/38) |
| 5.6.6 | MEMBER-01 | 멤버 초대 | 기존 초대 API |
| 5.6.7 | MEMBER-11 | 관리자 권한 넘기기 | [#49](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/49) |
| 5.7.1 | FEED-08 | 차단 목록 화면 | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.7.2 | FEED-08 | 차단 해제 | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.8.1 | MEMBER-08 | 번치 나가기(일반 멤버·공동 관리자) | [#25](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/25), [#38](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/38) |
| 5.8.2 | MEMBER-11 | 메인 관리자 나가기(새 관리자 지정) | [#25](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/25), [#38](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/38) |
| 5.8.3 | MEMBER-08, BUNCH-05 | 번치 나간 뒤 이동 | [#25](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/25), [#38](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/38) |
| 5.9.1 | BUNCH-09 | 번치 삭제 안내 | [#50](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/50) |
| 5.9.2 | BUNCH-09 | 번치 삭제 확인(문구 입력) | [#50](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/50) |
| 5.9.3 | BUNCH-09, NOTI-02 | 번치 삭제 후 처리·알림 | [#50](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/50) |
| 5.10.1 | FEED-02 | 다른 번치 프로필 화면(공개 번치) | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.10.2 | FEED-02 | 다른 번치 프로필 화면(비공개 번치) | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.10.3 | FEED-03 | 팔로우·팔로우 요청·팔로우 취소 | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.10.4 | FEED-07, FEED-08 | 다른 번치 더보기(차단하기·신고하기) | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.10.5 | FEED-08 | 번치 차단하기 | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.10.6 | FEED-08 | 차단된 번치에 들어갔을 때 | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.10.7 | FEED-07 | 번치 신고하기 | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.10.8 | FEED-02 | 다른 번치 멤버 목록 | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.11.1 | FEED-03 | 팔로워·팔로잉 목록 | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.11.2 | FEED-03 | 팔로우 요청 승인·거절 | [#51](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/51) |
| 5.12.1 | NOTI-01 | 알림 목록 화면 | [#28](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/28), [#48](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/48) |
| 5.12.2 | NOTI-01 | 알림 눌렀을 때 이동 | [#28](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/28), [#48](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/48) |
| 5.12.3 | NOTI-02, NOTI-04 | 알림 종류·문구 | [#28](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/28), [#48](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/48) |

## 후속 기능·수동 검증

후보 생성 알림 8번은 #26, 소셜 댓글/좋아요 알림 20/21번은 해당 피드 기능의 실제 쓰기에
연결한다. enum이나 임시 생산자만으로 이 세 종류를 완료로 표시하지 않는다. 번들 장소 상세(#27),
일반 피드·추천 계산, 계정 탈퇴(#29), 미정 가볼 곳 저장/목록·앱 화면 구현은 별도 계약 범위다.
실제 PostgreSQL·Redis·마이그레이션·통합 build 증거와 iOS/WebView/APNs 수동 증거를 구분한다.
