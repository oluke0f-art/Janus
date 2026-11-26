# Arquitetura

Papéis: **CÉREBRO** = celular controlado (roda o servidor scrcpy); **RECEPTOR** = celular com este app.

## O que foi lido no código do scrcpy 5.0.1 e o que isso implica

1. **Servidor reaproveitável.** `Server.main` lê opções `chave=valor` (`Options.java`): `scid`, `video`, `audio`,
   `tunnel_forward`, `new_display`, entre outras. O servidor abre sockets abstratos locais
   (`scrcpy` ou `scrcpy_<scid>`) via `DesktopConnection`; com `tunnel_forward=true` ele escuta e o cliente conecta
   (vídeo, depois áudio, depois controle, nessa ordem).
2. **Sem binário `adb` no RECEPTOR.** O app precisa falar o protocolo ADB por conta própria: pareamento por Wi-Fi
   (porta e código de pareamento), conexão (porta diferente), envio do jar para `/data/local/tmp`, execução com
   `app_process`, e abertura de streams `localabstract:scrcpy_<scid>` pela mesma conexão ADB. Isso dispensa `adb forward`.
   O pareamento ADB usa TLS + SPAKE2; **ainda não decidi** entre uma biblioteca existente e implementação própria,
   e não vou escolher sem poder baixar e testar dependências (decisão da fase 2).
3. **Monitor virtual real.** `NewDisplayCapture.startNew` chama
   `DisplayManager.createNewVirtualDisplay("scrcpy", w, h, dpi, surface, flags)` com o tamanho de `new_display=WxH/dpi`.
   Portanto a opção `new_display` já cria um display Android de verdade, sem mexer na tela física.
   Pontos a verificar na fase 6: o tamanho é alinhado ao alinhamento do codec (`Size.align`), então resoluções
   personalizadas podem ser arredondadas; limites de tamanho do encoder podem reduzir o vídeo (o display continua com
   o tamanho pedido, mas o stream pode sair menor).
4. **Áudio.** O upstream declara áudio para Android 11+ (API 30). Em aparelhos mais antigos o áudio não funciona.
5. **Protocolo de controle.** `ControlMessage`/`ControlMessageReader` serão preservados; o RECEPTOR implementa o lado
   cliente (serialização). No monitor virtual, o servidor já direciona eventos ao display criado (`PositionMapper`).

## Decisões de linguagem
- Kotlin: UI, sessão, ADB do lado do RECEPTOR, decodificação via `MediaCodec`.
- Java: servidor scrcpy (preservado).
- Rust/JNI: **não adotado agora.** Só será considerado na fase 10 se medições mostrarem gargalo no parsing/multiplexação.
- C/C++: sem uso por enquanto.

## Investigações abertas (sem conclusão ainda)
- **USB (fase 8):** exige o RECEPTOR como USB host falando ADB sobre a interface USB (classe 0xFF, subclasse 0x42)
  do CÉREBRO. Em tese possível via `UsbManager`; não verificado.
- **Samsung DeX (fase 9):** não se presume que um display virtual ative o DeX. Será investigado em aparelho real; limitações serão documentadas.
- **Codec (H.264/H.265/AV1):** decisão só com medição nos dois aparelhos.
- **Latência:** nenhum número prometido sem medição.

## Implementação das fases 2–5 (como funciona)

1. **Chave ADB**: RSA 2048 + certificado X.509 gerados uma vez em `filesDir` (`AppAdbManager`). O pareamento registra essa chave no CÉREBRO.
2. **Pareamento/conexão**: delegados ao libadb-android (`pair`, `connect`). Porta de pareamento e porta de conexão são campos separados.
3. **Envio do servidor**: `AdbSync.push` usa o serviço `sync:` (SEND/DATA/DONE) para gravar `/data/local/tmp/scrcpy-server-a2a.jar`.
4. **Início**: `shell:CLASSPATH=... app_process / com.genymobile.scrcpy.Server 5.0.1 scid=... tunnel_forward=true video=true audio=false control=true ...`.
   A saída do shell é lida e guardada (últimas 30 linhas) para aparecer no erro se o servidor falhar.
5. **Sockets**: sem `adb forward`. O app abre streams ADB `localabstract:scrcpy_<scid>` na mesma conexão, com até 150 tentativas de 100 ms
   (o servidor ainda pode não estar escutando). Ordem exigida pelo servidor: vídeo, depois controle.
6. **Fluxo de vídeo** (conferido em `DesktopConnection`/`Streamer`/`SurfaceEncoder`): 1 byte dummy → 64 bytes com o nome do aparelho →
   4 bytes de codec (`h264` = 0x68323634) → pacotes de 12 bytes de cabeçalho. Cabeçalho com bit 63 = pacote de sessão (largura e altura de 4 bytes cada);
   bit 62 = configuração (SPS/PPS, concatenado ao próximo quadro); bit 61 = quadro-chave.
7. **Decodificação**: `MediaCodec` H.264 com Surface, `KEY_LOW_LATENCY` (API 30+) e prioridade de tempo real. O decodificador é recriado a cada pacote de sessão
   (rotação/novo tamanho). Se um quadro for descartado, o app pede `RESET_VIDEO` ao servidor (no máximo 1 vez por segundo).
8. **Controle**: mensagens do protocolo do scrcpy (`ControlMessageReader`): toque (com id de ponteiro, multitoque), tecla, texto, voltar, girar origem.
   Posição enviada em pixels do vídeo + tamanho atual do vídeo (o servidor ignora o evento se o tamanho não bater).
   Uma única thread escreve no socket; mensagens acumuladas saem juntas. Mensagens do CÉREBRO (clipboard etc.) são lidas e descartadas.
9. **Monitor virtual**: apenas acrescenta `new_display=LxA[/dpi]` e `video_bit_rate`; o servidor cria o display via `DisplayManager.createNewVirtualDisplay`.
