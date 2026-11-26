# Janus

O **Janus** transforma dois celulares Android em um conjunto: um aparelho transmite sua tela e o outro permite visualizar e controlar essa tela por Wi-Fi. Também é possível criar monitores virtuais no aparelho controlado.

O projeto é baseado no **scrcpy 5.0.1**.

## Como funciona

- **RECEPTOR:** celular com o aplicativo Janus instalado. É por ele que você conecta, visualiza e controla o outro aparelho.
- **CÉREBRO:** celular que será controlado. Não precisa instalar o Janus nem outro aplicativo.

O Janus usa o ADB pela rede para iniciar temporariamente o componente do scrcpy no CÉREBRO. Esse componente não fica instalado permanentemente no aparelho.

## Como usar

1. Conecte os dois celulares à mesma rede Wi-Fi.
2. No **CÉREBRO**, ative as Opções do desenvolvedor e a **Depuração sem fio**.
3. Na Depuração sem fio, escolha **Parear dispositivo com código**.
4. No **RECEPTOR**, abra o Janus e selecione o aparelho encontrado. Se necessário, use a conexão manual e informe o IP, a porta de pareamento e o código exibidos no CÉREBRO.
5. Depois do pareamento, conecte usando a porta de conexão da tela principal da Depuração sem fio.
6. Escolha **Espelhar tela principal** ou crie um **monitor virtual**.

O pareamento é necessário na primeira conexão. **Depuração USB sozinha não substitui a Depuração sem fio** neste modo de conexão.

## Criado por OLUKE

- [GitHub](https://github.com/oluke0f-art)
- [YouTube](https://www.youtube.com/@OLUKE.F)
- [TikTok](https://www.tiktok.com/@oluke28)
- [Instagram](https://www.instagram.com/oluke.skull)
- [LivePix — apoie o projeto](https://livepix.gg/oluke)

## Licença

O Janus é distribuído sob a licença **GNU Affero General Public License v3.0 (AGPL-3.0)**, disponível no arquivo [`LICENSE`](LICENSE).

O projeto incorpora componentes do scrcpy 5.0.1 e mantém seus respectivos avisos de autoria e licença.