# notification-watcher

O `notification-watcher` é um microsserviço em Clojure responsável por monitorar mudanças de categoria em templates de mensagens na plataforma Gupshup para múltiplos WABA IDs (WhatsApp Business Account IDs).

Ele é projetado para:
1.  Obter uma lista de WABA IDs a serem monitorados, seja através de uma variável de ambiente (para mocking) ou de um serviço de gerenciamento de clientes (`customer-manager-service`).
2.  Consultar a API da Gupshup periodicamente para cada WABA ID para buscar o status atual dos templates de mensagens.
3.  Detectar templates que tiveram suas categorias alteradas (ex: de MARKETING para UTILITY).
4.  Expor um endpoint HTTP (`/changed-templates`) que outros serviços (como um `sms-notifier`) podem consumir para obter a lista de templates com categorias alteradas.

## Configuração

O serviço é configurado através de variáveis de ambiente:

| Variável                           | Descrição                                                                                                                               | Obrigatório | Exemplo                                                                 |
| ---------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- | ----------- | ----------------------------------------------------------------------- |
| `PORT`                             | Porta na qual o servidor HTTP irá rodar.                                                                                                | Não         | `8080` (valor padrão)                                                   |
| `GUPSHUP_TOKEN`                    | Token de API para autenticação com a API da Gupshup.                                                                                      | **Sim**     | `abcdef123456xyz`                                                       |
| `GUPSHUP_MOCK_MODE`                | Se `"true"`, o serviço usará dados mockados para as chamadas à API da Gupshup, em vez de fazer chamadas reais. Útil para desenvolvimento.   | Não         | `true`                                                                  |
| `MOCK_CUSTOMER_MANAGER_WABA_IDS`   | Uma lista de WABA IDs separados por vírgula para serem usados quando o `customer-manager-service` real não está disponível ou para testes. | Não         | `111222333,444555666`                                                   |
| `CUSTOMER_MANAGER_URL`             | A URL base do `customer-manager-service` de onde a lista de WABA IDs ativos será buscada. Ignorado se `MOCK_CUSTOMER_MANAGER_WABA_IDS` estiver definido. | Não         | `http://customer-manager-service:8000`                                  |

**Ordem de precedência para WABA IDs:**
1.  Se `MOCK_CUSTOMER_MANAGER_WABA_IDS` estiver definido, seus valores serão usados.
2.  Caso contrário, se `CUSTOMER_MANAGER_URL` estiver definido, o serviço tentará buscar os WABA IDs desta URL.
3.  Se nenhum dos dois estiver definido, o watcher não terá WABA IDs para processar (um erro será logado).

**Ordem de precedência para dados da Gupshup:**
1.  Se `GUPSHUP_MOCK_MODE` for `"true"`, dados mockados serão gerados para os WABA IDs obtidos (conforme a lógica acima). A estrutura dos dados mockados simula a resposta da API da Gupshup, incluindo templates com e sem `oldCategory`.
2.  Caso contrário, chamadas reais à API da Gupshup serão feitas.

## Endpoints da API

### `GET /`
*   **Descrição:** Endpoint básico para verificar se o serviço está no ar ("keep-alive").
*   **Resposta (200 OK):**
    ```text
    Serviço Notification Watcher está no ar.
    ```

### `GET /changed-templates`
*   **Descrição:** Retorna uma lista de templates que foram identificados com mudança de categoria desde a última verificação bem-sucedida. A lista é atualizada periodicamente pelo worker em background.
*   **Resposta (200 OK):**
    *   Corpo: Um array JSON contendo objetos de template. Cada objeto representa um template que mudou de categoria.
    *   Exemplo de corpo da resposta:
        ```json
        [
          {
            "id": "tpl_changed_111222333_2",
            "elementName": "Template Alterado 111222333 Bravo",
            "wabaId": "111222333",
            "category": "UTILITY",
            "oldCategory": "MARKETING",
            "status": "APPROVED",
            "language": "en_US"
          },
          {
            "id": "tpl_another_changed_444555666_4",
            "elementName": "Outro Alterado 444555666 Delta",
            "wabaId": "444555666",
            "category": "AUTHENTICATION",
            "oldCategory": "UTILITY",
            "status": "APPROVED",
            "language": "pt_BR"
          }
        ]
        ```
    *   Se nenhum template com mudança for encontrado, retorna um array JSON vazio `[]`.

## Como Executar (Desenvolvimento)

1.  **Clone o repositório.**
2.  **Instale as dependências do Leiningen:**
    ```bash
    lein deps
    ```
3.  **Configure as variáveis de ambiente:**
    Crie um arquivo `.env` (se usar uma ferramenta como `dotenv` com Leiningen, ou exporte-as manualmente):
    ```bash
    export GUPSHUP_TOKEN="seu_token_real_ou_mock"
    export GUPSHUP_MOCK_MODE="true"
    # Para usar WABA IDs mockados:
    export MOCK_CUSTOMER_MANAGER_WABA_IDS="waba_id_1,waba_id_2,waba_id_3"
    # OU, para usar um customer-manager-service real (exemplo):
    # export CUSTOMER_MANAGER_URL="http://localhost:8001"
    # (descomente e ajuste conforme necessário, e remova ou comente MOCK_CUSTOMER_MANAGER_WABA_IDS)
    export PORT="8080"
    ```
4.  **Execute a aplicação:**
    ```bash
    lein run
    ```
    O servidor iniciará na porta especificada (padrão 8080) e o watcher começará a rodar em background.

## Como Construir para Produção

Para criar um uberjar (arquivo JAR auto-contido):
```bash
lein uberjar
```
O arquivo JAR resultante estará em `target/uberjar/notification-watcher-0.1.0-SNAPSHOT-standalone.jar` (o nome pode variar ligeiramente). Você pode então executar este JAR em um ambiente Java:
```bash
java -jar target/uberjar/notification-watcher-0.1.0-SNAPSHOT-standalone.jar
```
Lembre-se de configurar as variáveis de ambiente necessárias no ambiente de produção.

## Exemplo de Configuração no Render

Se você estiver fazendo deploy no Render.com, você configuraria as variáveis de ambiente na seção "Environment" do seu serviço:

*   `GUPSHUP_TOKEN`: `seu_token_real_da_gupshup`
*   `PORT`: `10000` (O Render geralmente define a porta, mas se você precisar especificar, use a que o Render espera)

Para **iniciar com WABA IDs mockados** no Render:
*   `GUPSHUP_MOCK_MODE`: `true`
*   `MOCK_CUSTOMER_MANAGER_WABA_IDS`: `seu_waba_id_atual_do_gupshup,outro_waba_id_se_necessario`

    *Substitua `seu_waba_id_atual_do_gupshup` pelo App ID da Gupshup que você usa hoje. Você pode adicionar mais IDs separados por vírgula.*

Para **conectar ao `customer-manager-service` real** no futuro (depois que ele estiver pronto e deployado no Render com sua própria URL de serviço interna, por exemplo `customer-manager.onrender.com`):
*   `GUPSHUP_MOCK_MODE`: `false` (ou omita, pois o padrão é não mockar a Gupshup)
*   `CUSTOMER_MANAGER_URL`: `http://customer-manager.onrender.com` (substitua pela URL correta do seu serviço)
*   Remova ou deixe em branco `MOCK_CUSTOMER_MANAGER_WABA_IDS`.

O serviço `notification-watcher` então chamaria `http://customer-manager.onrender.com/internal/customers/active-waba-ids` para obter a lista de WABA IDs.
