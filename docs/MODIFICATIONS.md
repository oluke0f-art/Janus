# Modificações em relação ao scrcpy 5.0.1

Base: scrcpy 5.0.1, GPL-3.0, © Genymobile / Romain Vimont.

## Fase 1
- Mantido: `server/` (código Java, AIDL, `build.gradle`, testes) sem alteração de código-fonte.
- Removidos do repositório (não usados neste projeto): cliente C (`app/` original), scripts meson,
  `server/meson.build`, `server/build_without_gradle.sh`, `server/scripts/build-wrapper.sh`, CI do GitHub, scripts de release.
- `config/` e o wrapper do Gradle foram copiados; a versão do Gradle no wrapper foi trocada de 9.3.1 para 8.11.1
  e o checksum removido.
- `build.gradle` raiz e `settings.gradle` foram reescritos (plugins declarados na raiz, módulo `:app` adicionado).
- Novo: módulo `app/` (Kotlin), `docs/`.

Alterações futuras no servidor (fases 6+) serão listadas aqui, arquivo por arquivo.

## Fases 2–5
- `server/`: **nenhuma alteração de código.** O app usa o servidor como está, só com opções de linha de comando.
- `app/`: código novo em Kotlin (ADB, sessão, decodificação, controle, interface). Nenhum código do cliente C do scrcpy foi usado;
  o formato do protocolo foi reimplementado a partir da leitura do servidor.

## Correção (teste real do usuário)
- Sintoma: após parear/conectar, o app mostrava só “scrcpy-server” em vermelho. Era `FileNotFoundException` do asset: o servidor não foi
  empacotado no APK. Corrigido em `app/build.gradle` (caminhos `File` simples, `include '*.apk'`, o build agora falha se o servidor não for gerado)
  e `ServerAsset.open` (mensagem de erro explicativa).

## Correção 2 (erro de compilação reportado pelo usuário)
- `Unresolved reference 'X509CertImpl'`: a geração do certificado ADB não usa mais `sun.security.x509` (biblioteca sun-security-android removida).
  O certificado X.509 autoassinado (RSA/SHA-256) agora é montado em DER no próprio `AppAdbManager.kt` e interpretado pelo `CertificateFactory` do Android.
  Chaves e certificados já gravados continuam válidos (não é preciso parear de novo).
- Servidor fora do APK: empacotamento trocado para `androidComponents.onVariants` + `addGeneratedSourceDirectory` (API oficial do AGP).

## Correção 3 (revisão após novos erros de build)
- `KeyboardType.VisiblePassword` não existe no Compose: trocado por `KeyboardType.Password`.
- `getApplication()` com tipo explícito no ViewModel.
- Embutir o servidor: a tarefa agora procura qualquer `.apk` em `server/build` (em disco, na execução), usa `t.dependsOn(...)` com receptor explícito
  e `evaluationDependsOn(':server')`; se não achar, o erro lista o que existe em `server/build/outputs`.
- Revisão estática: todas as chaves de opção enviadas ao servidor (`scid`, `log_level`, `tunnel_forward`, `video`, `audio`, `control`,
  `video_codec`, `clipboard_autosync`, `new_display`, `video_bit_rate`) existem em `Options.java` do 5.0.1.

## Correção 4 (teste real: travava em "Conectando ao vídeo…")
- Deadlock de protocolo: `DesktopConnection.open()` do servidor só devolve (e só então envia nome do aparelho + cabeçalho do vídeo) depois de aceitar
  também o socket de controle. O cliente esperava o nome do aparelho antes de abrir o controle. Agora o controle é aberto logo após o byte dummy.

## Versão 0.3.0 (Janus)
- `server/`: ainda **sem alterações de código**. Novas opções usadas: `audio=true`, `audio_codec=raw`, `display_id=N`, `list_displays=true`.
- Novos: lista/conexão a telas existentes, encerramento do servidor por `scid` ao fim da sessão (`pkill`), fechar todos os servidores,
  áudio PCM (`AudioPlayer`), mouse físico e rolagem, modo touchpad, tela cheia com gesto de 4 dedos × 3 toques, nome Janus e ícone.
- Protocolo conferido no servidor: ordem de sockets vídeo → áudio → controle; mensagens de mouse (`POINTER_ID_MOUSE = -1`, botões, `HOVER_MOVE = 7`)
  e rolagem (tipo 3, ponto fixo de 16 bits com faixa ±16).

## Versão 0.4.0
- `server/`: ainda **sem alterações de código**.
- Toques: removida a supressão de 4+ dedos; todos os ponteiros (até 10, `PointersState.MAX_POINTERS` do servidor) são enviados.
  O gesto de sair da tela cheia (4 dedos × 3 toques) passou a ser apenas detectado, sem bloquear o envio.
- Controles de jogo: `GamepadForwarder` cria um gamepad HID virtual (mensagens UHID 12/13 do protocolo de controle) com o descritor e o
  layout de relatório portados de `app/src/hid/hid_gamepad.c` (scrcpy 5.0.1). Botões, sticks, gatilhos e direcional (teclas e eixos HAT).
- Conexão fácil: `AdbDiscovery` usa mDNS (`_adb-tls-connect._tcp` e `_adb-tls-pairing._tcp`) para achar IP/porta sem digitar;
  `pairAndConnect` pareia e conecta automaticamente.
- Interface refeita: tema próprio, `ConnectScreen`, `HomeScreen`, barra de controle com menu "Mais".

## Versão 0.5.0
- `server/`: ainda **sem alterações de código**.
- Sessões independentes da tela: `SessionManager` + `KeepAliveService` (primeiro plano, tipo connectedDevice). `ScrcpySession` não depende mais de Surface:
  `attachSurface`/`detachSurface` ligam e desligam a imagem; ao anexar, pede `RESET_VIDEO` (o servidor reinicia o codificador, envia novo pacote de sessão,
  configuração e quadro-chave; o display virtual é preservado, ver `NewDisplayCapture.start`).
- Cada sessão usa o próprio jar (`janus-server-<scid>.jar`); o servidor apaga o jar que usou ao sair.
- Lista de monitores via `list_displays`, atualizada a cada 8 s; id do monitor virtual lido do log do servidor ("New display: … (id=N)").
- Fechar sem dono: `ps -A -o PID,ARGS | grep '[s]cid='` + `kill`, poupando os `scid` das sessões ativas.
- Pareamento sempre visível na tela de conexão (código + detecção automática; IP:porta só se a janela não for detectada).
- Interface redesenhada (cartões com borda, rótulos de seção, cartão por monitor).

## Versão 0.5.1 (teste real)
- A tela principal (#0) agora aparece sempre na lista de monitores, mesmo se a consulta de telas falhar.
- `DeviceTools.runShell`: leitura manual da saída; o fim do stream chegava como exceção ("Stream closed") e descartava a lista. Se a resposta não
  trouxer telas, o erro mostra o início da saída recebida.
- `AdbSync`: a mensagem final QUIT passou a ser tolerante a falha.
- Gamepad: relatórios de eixos limitados a ~125/s com envio final garantido, botões imediatos, sem reenvio de relatórios iguais, zona morta com
  reescala e quantização (ruído do analógico), gatilho de eixo e de tecla combinados por máximo.
- Resoluções do monitor virtual em botões curtos, com a resolução escolhida em texto.

## Versão 0.6.0 (visual)
- Tema único escuro (preto + violeta `#5A14F0`), componentes em caixa (`BoxCard`, `ChunkyButton`, `ChunkChip`) com sombra dura.
- Monitores e aparelhos encontrados em caixinhas de duas colunas; mini-tela desenhada na proporção da resolução.
- Menu (três barrinhas) com links do criador; rodapé "Me pague um café" no fim da rolagem (conteúdo com altura mínima igual à da tela).
- `server/`: sem alterações de código.

## Versão 0.6.1 (teste real: pareamento recusado)
- Causa: o Android fecha a janela "Parear com código" ao sair das Configurações; o app seguia mostrando a janela antiga (mDNS demora a remover)
  e tentava parear numa porta que já não escutava (ECONNREFUSED). Limpar os dados do app também gera uma chave ADB nova, o que exige parear de novo.
- Novo: `PairingService` + `PairingReceiver`: pareamento pela notificação (campo de texto na notificação, `RemoteInput`) enquanto a janela de pareamento
  continua aberta nas Configurações; conecta sozinho depois e avisa a interface por `ConnectionHub`.
- `AdbDiscovery.forgetPairing` descarta a janela antiga quando a conexão é recusada; `AdbErrors` traduz os erros de rede.
- Pílula "Janela detectada" passa a mostrar IP:porta completos (até 2 linhas).

## Versão 0.7.0
- `server/`: sem alterações de código.
- Vários aparelhos: uma instância de `AppAdbManager` por aparelho (chave ADB compartilhada, `AppAdbManager.create`); `ScrcpySession` e
  `SessionManager.Info` ganharam `deviceId`; o ViewModel guarda `devices`/`currentDeviceId`, lista de telas por aparelho e `switchViewing`
  para trocar de monitor entre aparelhos. `ConnectionHub.Incoming` entrega a conexão feita em segundo plano (pareamento por notificação).
- Touchpad: cursor desenhado pelo app (o servidor injeta eventos de mouse via SDK, que o Android não desenha na tela).
- Áudio: a mensagem "indisponível" agora traz o motivo lido do log do servidor; indicador de taxa de dados; removido o descarte baseado em
  `available()` (podia descartar todo o áudio dependendo do stream).
- Correção: ao sair da tela de espelhamento a sessão é desanexada da Surface antes de o id ser zerado (antes a Surface destruída ficava ligada).
