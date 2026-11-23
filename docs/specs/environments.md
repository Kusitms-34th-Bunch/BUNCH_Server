# 개발·운영 실행 환경 계약 (#212)

## 확정 범위

가비아 4vCPU/8GB 단일 서버와 Linux `bunch` 계정을 유지한다. Kubernetes·추가 인스턴스·서버 빌드를 도입하지 않는다.
실제 적용 순서와 명령은 [배포 안내](../../deploy/README.md)를 따른다. 아래 구성은 실제 적용 완료를 뜻하지 않는다.
현재 개발 환경·데이터를 운영으로 승격하지 않는다. 별도 빈 운영 환경은 개발 hostname 일정과 무관하게
비공개로 준비할 수 있지만, 현재 `api.bunch.kr` 연결·개발 서버는 유지한다. 개발 hostname과 테스트 앱
주소 변경 일정, 최종 운영 라우팅 일정은 사용자 확정 전까지 보류한다.

| 구분 | 개발 | 운영 | 공용 관측 |
| --- | --- | --- | --- |
| 경로 | `~/dev` | `~/prod` | `~/monitoring` |
| 브랜치 | `dev` | `main` | `main`의 관측 변경만 별도 활성화 |
| Compose 프로젝트 | 기존 실측 이름 유지(현재 설정 `bunch`) | `bunch-prod` | 기존 `bunch` 유지, 별도 Compose 파일·관측 전용 네트워크 |
| API·WebSocket | 현재 `api.bunch.kr`, 개발용 주소·일정 추후 확정 | 향후 `api.bunch.kr`, 준비 단계에서는 미연결 | 관리자 전용 hostname |
| DB·Redis 볼륨 | 기존 실측 이름, external | 새 `bunch-prod-pgdata`, `bunch-prod-redis-data`, external | 기존 모니터링 볼륨 이름 유지 |
| 네트워크 | 기존 개발 네트워크 유지 | 독립 `bunch-prod-default` | dev/prod 관측 네트워크 각각에 Prometheus만 접속 |
| R2 버킷 | `bunch-dev` | `bunch-prod` | 사용하지 않음 |

앱·PostgreSQL·Redis·Tunnel은 환경마다 독립 실행한다. 앱만 자기 환경의 관측 네트워크에도 연결하고
`dev-app` / `prod-app` alias를 쓴다. DB/Redis와 관리 포트는 호스트에 게시하지 않는다.
서로 다른 앱이 같은 네트워크에 들어가지 않으며 공용 관측 서비스는 DB/Redis에 접속하지 않는다.
기존 프로젝트·볼륨을 외관상 이름 변경하려고 재생성하지 않는다. `down -v`·prune·볼륨 삭제는 금지한다.
운영 DB는 빈 DB로 시작하고 Flyway로 생성한다. 개발 DB나 Redis를 운영으로 복제하지 않는다.

## 환경·이미지·관측

- `.env`는 환경 디렉터리에 운영자가 생성하며 CI가 전송·출력하지 않는다. JWT, DB 암호, R2 키, Tunnel 토큰은 환경별로 다르다.
- R2 계약은 `R2_ENDPOINT`(버킷명 제외), `R2_BUCKET`, `R2_ACCESS_KEY_ID`, `R2_SECRET_ACCESS_KEY`다. 서명 기본 만료는 #213 코드의 1800초다.
- Apple/Google/Kakao 앱·번들 ID 분리는 보류한다. Cloudinary 키는 #213 최종 복사·검증과 DB 매핑 후 새 R2 앱 기동 전에 앱 환경에서 제거한다.
  일회성 도구와 기존 앱 복구 설정에는 보존하며 원본 삭제·계정 해지는 별도 후속 작업이다.
- 앱은 CI가 게시한 SHA 태그를 pull한 뒤 digest로 고정한다. 환경별 `.release.env`가 `APP_IMAGE`를 기록하며 서로 공유하지 않는다.
- 앱 환경의 PostgreSQL·Redis·Tunnel 이미지도 실측 digest로 고정하고 CI가 갱신하지 않는다. 공용 관측은 정확한 버전 또는 digest를 사용한다.
- 컨테이너 자원 상한 합계는 4CPU/8GB보다 작게 두고 OS·Docker 여유를 남긴다. 상한은 실측 후 조정하며 성능 보장은 아니다.
- 단일 Alloy/Loki/Prometheus/Grafana가 `environment=dev|prod` 라벨로 로그·메트릭·알림·대시보드를 구분한다.
  [모니터링 Spec](monitoring.md)의 타이밍·로그 보관·수집 비밀값 정책을 유지한다.
- 공용 모니터링의 별도 `.env`에는 DB/JWT/R2 키를 넣지 않는다. 각 서비스에는 필요한 값만 전달한다.

## 전환과 실패 계약

1. 이전 `main → ~` 배포를 중지하고 실행 중 배포가 없는지 확인한다. 새로운 자동 배포는 준비 표식 없이는 실패해야 한다.
2. 기존 프로젝트·네트워크·볼륨·이미지·bind mount를 비밀값 없이 확인한다. 복구 가능한 백업을 별도 보관하고 복구 시험을 마친다.
3. **운영 비공개 준비:** 개발 hostname·앱 전환을 기다리지 않고 `~/prod`에 독립 키·새 DB/Redis·빈 `bunch-prod`를 준비한다.
   R2 앱 이미지와 내부 health/Flyway·데이터 격리를 검증하되 운영 Tunnel·공개 hostname·자동 배포는 활성화하지 않는다.
   기존 개발 서버와 데이터는 그대로 유지한다. 검사에 필요한 개발 환경 파일 준비는 개발 DNS/앱 전환을 뜻하지 않는다.
4. **개발 전환:** 개발용 주소와 테스트 앱 API·WebSocket 변경을 실제 검증한다. `dev.bunch.kr`은 확정 일정에만 연결한다.
   기존 데이터의 R2 이전·개발 경로 활성화는 #213의 별도 점검·동결·DB 매핑 절차를 따른다.
5. **공개 전 필수 조건:** prod에서 임시 `/api/v1/auth/dev/token` API를 차단한 이미지와 검증 근거를 확보한다.
   현재 코드에서는 prod에도 열리므로 비공개 준비 완료만으로 운영 공개 가능 상태로 판단하지 않는다.
6. **최종 라우팅·자동 배포:** 사용자 일정 확정, 실제 테스트 앱 주소 변경 확인, 운영 검증과 위 공개 차단 사항 해결 후에만
   조정 채팅이 `api.bunch.kr`을 새 운영 환경에 연결한다. 준비 표식과 환경별 자동 배포는 각각 명시적으로 활성화한다.

실패 시 새 배포를 중지하고 이전 고정 이미지·설정으로 앱만 복구한다. Flyway가 변경한 스키마는 이미지 rollback만으로 되돌아가지 않는다.
DB 복원은 별도 검증한 백업과 쓰기 중단이 필요하다. 데이터 볼륨과 기존 홈 파일은 성공 확인 전 삭제하지 않는다.

## 완료 기준

- Compose 격리·필수 변수·볼륨 미생성·자원 상한·CI 분기/게이트 검증, 실제 모니터링 smoke, 전체 build 결과를 보고한다.
- 실제 서버 자원·외부 hostname·iOS WebSocket·복구·Discord 수신은 조정 채팅의 수동 검증으로 구분한다.
- [협업 규칙](../../CONTRIBUTING.md)과 [모니터링 Spec](monitoring.md)도 같은 환경별 배포 계약을 따른다.

서버 읽기 전용 확인(2026-11-22, 조정 채팅): bunch 계정·프로젝트, `bunch-pgdata` / `bunch-redis-data`, `/home/bunch/compose.yaml` 및 `.env` 경로 확인. 모니터링 볼륨 이름·이미지 digest는 적용 시 재확인한다.
