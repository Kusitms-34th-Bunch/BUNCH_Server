# 서버 모니터링·Discord 알림 Spec

## 목적과 범위

BUNCH 단일 운영 서버의 요청 로그·메트릭을 Grafana 대시보드 하나에서 조회하고,
첫 5xx와 앱 메트릭 수집 중단을 Discord로 알린다. 이 문서는 승인된 최소 구현 계약이며,
이전의 오류율·지연·힙 임계값 계획을 대체한다. 실제 운영 배포·웹훅 수신 완료를 뜻하지 않는다.
제품 API·DB 스키마·앱 내 알림·APNs 계약은 바꾸지 않는다.
구현은 [이슈 #13](https://github.com/Kusitms-34th-Bunch/BUNCH_Server/issues/13)에서 추적한다.

| 구성 | 책임·내부 연결 |
| --- | --- |
| Actuator·Micrometer Prometheus | 앱 `8081`에서 health·Prometheus 제공 |
| Alloy | Compose project `bunch`, service `app`의 Docker 로그 → Loki |
| Loki | 요청 로그 보관·최근 1분 5xx 집계, 내부 `3100` |
| Prometheus | `app:8081/actuator/prometheus` 수집, 내부 `9090` |
| Grafana | 메트릭·로그 대시보드, 두 알림 평가·Discord 전송, 내부 `3000` |

`deploy/compose.yaml`과 `deploy/monitoring/`이 실행 설정의 원본이다.
모니터링 이미지 버전은 고정 태그를 사용한다. 별도 Alertmanager·앱 Discord appender·
전송 서버·recording rule·배포 이력 시스템은 두지 않는다.
외부 HTTPS·호스트 전체·Tunnel·DB/Redis 전용 감시, 고가용성과 분산 추적은 범위 밖이다.

## 앱 관측 계약

- 승인된 의존성은 `spring-boot-starter-actuator`, `micrometer-registry-prometheus`다.
- API `8080`과 관리 `8081`을 분리한다. 관리 HTTP 노출은 `/actuator/health`,
  `/actuator/prometheus`만 허용하고 health 상세는 숨긴다. 내부 수집에 제품 JWT를 요구하지 않는다.
  API 포트나 공개 Tunnel에서는 관리 정보를 제공하지 않는다.
- 관리 포트·Alloy·Loki·Prometheus의 호스트 포트를 열지 않는다. Grafana는 기존 Tunnel의
  별도 관리자 HTTPS hostname으로만 연결하며 익명 접근·회원 가입을 끄고 인증을 유지한다.
- Prometheus job은 `bunch`, 로그·알림 식별 라벨은 `service=bunch`, `environment=prod`다.
  HTTP 메트릭의 URI는 매핑 템플릿을 사용한다. 원문 URL·query·토큰·회원 ID·요청 ID를 라벨로 쓰지 않는다.
  HTTP 처리 시간 히스토그램을 활성화하여 p95를 계산하고 대시보드에서 관리 요청을 제외한다. `up=1`은 메트릭 수집 성공이며 API·DB·Redis 정상 보장이 아니다.
- 기존 `traceId / eventId / userId` MDC·요청 시작/종료·MDC 정리·초대 토큰 마스킹을 유지한다.
  MDC 필터를 JWT 필터보다 먼저 실행하여 인증 필터에서 조기 반환한 503도 요청 종료 로그로 남긴다.
  응답의 `error.detail.eventId`로 같은 요청을 찾는다.
- Alloy는 날짜로 시작하는 로그와 후속 스택 행을 묶고 수집 위치를 볼륨에 보관한다.
  Loki 인덱스 라벨은 `service / environment`, ID·level은 structured metadata로 사용한다.
  요청/응답 본문·인증 헤더·쿠키·원문 토큰·사진 데이터를 추가 수집하지 않는다.
- **5xx 원본은 요청 종료 로그의 `status=5xx`다.** WARN으로 기록된 처리 오류도 포함하며,
  ERROR 레벨 로그 수나 Prometheus counter 증가량으로 대체하지 않는다. 따라서 첫 실패 요청도
  counter의 이전 scrape 유무와 무관하게 검출한다. 4xx·일반 WARN/ERROR 로그는 이 알림의 대상이 아니다.

## 알림 계약

평가는 **10초 간격**이다. Grafana의 파일 프로비저닝으로 규칙·contact point·notification policy를 관리한다.

| 규칙 | 조건 | `for` | 최초 대기 | 반복 | 복구 전송 |
| --- | --- | --- | --- | --- | --- |
| 첫 5xx | Loki 최근 1분 요청 종료 로그 중 5xx 합계 ≥ 1 | `0s` | `group_wait: 0s` | `5m` | 끔 |
| 앱 수집 중단 | Prometheus `up{job="bunch"}=0` 또는 대상 소실 | `1m` | `group_wait: 0s` | `5m` | 켬 |

규칙별로 묶으며 `service / environment / alertname`을 식별에 사용한다.
5xx는 Loki에 수집된 다음 평가에서 바로 firing한다. 정상 트래픽이나 최소 요청 수를 기다리지 않는다.
최근 1분 창에서 사라지면 정상 상태로 돌아가지만 Discord 복구 메시지는 보내지 않는다.
반복 5분은 같은 firing 그룹이 지속될 때의 간격이다. 정상으로 돌아간 뒤 새 5xx가 발생하면
새 firing 전환으로 다시 전송될 수 있으므로 전역 5분 rate limit을 뜻하지 않는다.
앱 수집 중단은 1분 유지 후 알리고 수집 재개 시 복구를 보낸다.

무트래픽은 장애가 아니다. 5xx 조회식은 빈 결과를 `vector(0)`으로 보완하고,
수집 중단은 대상 소실을 조회식에서 명시적으로 포함한다. 앱 수집 규칙의 실제 No Data/Error는
`KeepLast`로 기존 상태를 유지하여 조회 실패를 정상 복구로 보내지 않는다.
5xx 규칙의 No Data/Error는 각각 Grafana의 `NoData`/`Error` 상태로 다룬다.
이때 Grafana 기본 데이터 소스 알림이 발생할 수 있으므로 제품 규칙 두 개와 구분해 확인한다.
Loki/Grafana 자체 중단은 이 두 규칙으로 확실히 알릴 수 없으며, 관측 실패가 5xx가 없다는 증거도 아니다.

### 실제 탐지 지연

`for: 0s`는 요청 순간의 전송을 뜻하지 않는다. 요청 종료 → Docker 로그 → Alloy 읽기·
멀티라인 대기·배치 전송 → Loki 조회 가능 → 다음 Grafana 평가(정상 주기에서 최대 약 10초) →
Discord 전송 시간이 더해진다. `group_wait: 0s`는 추가 묶음 대기만 없앤다.
현재 명시하지 않은 Alloy 기본값은 multiline 대기 최대 3초와 write 배치 대기 최대 1초다.
짧은 정상 수집 지연에서 첫 5xx를 다음 평가에 잡는 것이 목표이며, 장애·부하·네트워크가 있으면
10초 내 수신을 보장하지 않는다. 수집이 1분 창보다 늦으면 놓칠 수 있다.

최초 기동은 이미지 pull·컨테이너 준비·Grafana 프로비저닝·Alloy 연결·첫 scrape와 평가까지 필요하다.
Grafana/Loki가 준비되기 전에는 즉시 탐지할 수 없다. Docker 대상 발견의 기본 갱신 주기는 1분이므로
앱 컨테이너 재생성 후 새 대상 연결에도 지연이 추가될 수 있다. 앱 수집 중단 탐지에는 다음 scrape까지의 시간,
다음 평가까지의 시간, `for: 1m`과 전송 시간이 더해진다. Prometheus 수집 간격은 15초다.
기동/규칙 재설정 시 pending 상태가 다시 시작될 수 있어 재기동 직후의 즉시 장애 알림도 보장하지 않는다.
이 지연 설명은 [Grafana 평가 주기·pending 정의](https://grafana.com/docs/grafana/latest/alerting/fundamentals/alert-rule-evaluation/)와
[Alloy multiline](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.process/),
[write batch](https://grafana.com/docs/alloy/latest/reference/components/loki/loki.write/),
[Docker discovery](https://grafana.com/docs/alloy/latest/reference/components/discovery/discovery.docker/)의 기본값을 적용한 추정이며 실측 보장은 아니다.

### 전송·비밀값

- 앱·DB·Redis·Tunnel·Alloy·Grafana는 공통 `.env`를 `env_file`로 읽는다.
  `DISCORD_WEBHOOK_URL`과 Grafana 설정도 같은 파일에 두며 해당 서비스들에 전체 변수가 전달된다.
- `GF_SERVER_ROOT_URL`은 실제 Grafana HTTPS URL이며 링크 생성에 사용한다.
  `GF_SECURITY_ADMIN_PASSWORD`는 초기 관리자 생성용이다. 기존 계정 비밀번호 변경은 별도 수행한다.
- 메시지는 규칙·서비스·환경·발생/복구·조건·Grafana 링크로 원인을 확인할 수 있게 한다.
  원문 로그·스택·사용자 정보·토큰·전체 멘션을 넣지 않는다.
- 개발·CI에서는 실제 운영 웹훅을 사용하지 않는다. 로컬 HTTP stub 검증과 운영 Discord 실제 수신은
  별도 증거다. Grafana 전송 오류가 앱 요청 처리에 영향을 주지 않는다.

## 대시보드·보관

로그·메트릭을 대시보드 하나에서 조회한다. 앱 수집 상태, 제품 API RPS·5xx,
HTTP 지연 p95, JVM 힙·Hikari 풀과 요청 로그를 확인하고 traceId/eventId로 검색한다.
무트래픽의 비율·지연은 계산 불가로 다루며 수집 실패를 정상 0으로 덮지 않는다.
프로비저닝 파일과 고정 UID가 원본이며 UI 변경은 저장소에 반영해야 재현된다.

- Loki·Prometheus 보관은 7일이다. 실제 삭제에는 compaction 지연이 있고 디스크 압박 시
  수집·보관이 실패할 수 있으므로 최소 7일 보존 보장은 아니다.
- Docker 로그는 파일당 `10m`, 최대 `3`개로 회전한다. 바쁜 서비스의 로그가 수집 전에 회전하면 유실될 수 있다.
- Alloy 위치·Loki·Prometheus·Grafana 데이터는 영속 볼륨을 사용한다.
  기존 `bunch-pgdata / bunch-redis-data`를 보존하며 무손실·정확히 한 번 수집은 보장하지 않는다.
- Alloy의 Docker 소켓 `:ro`는 Docker API 권한을 읽기 전용으로 제한하지 않는다.
  이 접근 권한을 다른 모니터링 서비스에 추가하지 않는다.

## 배포·검증

[배포 안내](../../deploy/README.md)의 `/bunch` 절차를 따른다.
CI는 `git diff`로 앱/모니터링 두 플래그를 계산하고 공통 Compose·환경 예제·CI 변경은 둘 다 켠다.
문서만 변경하면 필수 이름 `build`를 skipped로 남기며 배포하지 않는다.
모니터링만 변경하면 설정 검증 후 네 모니터링 서비스를 함께 재생성하고 앱은 재시작하지 않는다.
앱 변경에서만 `latest` 이미지를 빌드·게시·pull하고 app만 갱신한다.
PostgreSQL·Redis·cloudflared는 CI가 재시작하지 않는다.

자동 검증은 다음과 같다. 실제 실행 결과는 완료 보고에 남기며 이 목록 자체가 통과 증거는 아니다.

```bash
test -f deploy/.env || cp deploy/.env.example deploy/.env
bash deploy/monitoring/check.sh
python3 deploy/monitoring/smoke_test.py
DB_URL=jdbc:postgresql://localhost:5432/bunch_test ./gradlew clean build
```

- 설정 검사: 비밀값 출력 없는 Compose `config --quiet`, Prometheus·Alloy·Loki 설정과 Grafana 프로비저닝.
- 로컬 HTTP stub: 실제 Grafana/Loki를 기동해 요청 종료 로그의 5xx 조회와 알림 프로비저닝·전송을 검증.
  stub는 운영 Discord를 호출하지 않는다. 실행 범위·대기 시간은 테스트 스크립트를 따른다.
- 앱 테스트: 관리 포트 분리·health/prometheus만 노출·인증 회귀·JWT 이전 503 요청 종료 로그·MDC 정리·마스킹.
- 별도 운영 확인: 첫 기동 후 메트릭·로그 조회, 지정 시험 채널의 발생/복구 수신·링크,
  재생성 후 데이터 유지·RAM/디스크 여유. 실제 운영 웹훅 전송은 별도 승인 범위다.

실패는 해당 명령에서 작업을 중단하며 수동 재실행한다. 이전 Git의 Compose·monitoring 파일을
복원하는 절차는 배포 안내에 둔다. 자동 rollback·digest pinning·manifest·staging은 도입하지 않는다.

## 참고

- [Spring Boot 관리 HTTP 포트](https://docs.spring.io/spring-boot/3.5/reference/actuator/monitoring.html)
- [Grafana 알림 파일 프로비저닝](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/file-provisioning/)
- [Grafana Discord contact point](https://grafana.com/docs/grafana/latest/alerting/configure-notifications/manage-contact-points/integrations/configure-discord/)
- [Grafana 알림 타이밍](https://grafana.com/docs/grafana/latest/alerting/configure-notifications/manage-notification-policies/)
