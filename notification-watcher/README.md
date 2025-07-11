# Notification Watcher Service

Serviço para monitorar templates da Gupshup e notificar sobre mudanças de categoria em múltiplos WABA IDs.

## Como Configurar

Antes de executar o serviço, você precisa configurar as seguintes variáveis de ambiente:

*   `GUPSHUP_TOKEN`: (Obrigatório) O token de acesso para a API da Gupshup. Este token é usado para autenticar as requisições que buscam a lista de templates.
*   `CUSTOMER_MANAGER_URL`: (Opcional) A URL base do `customer-manager-service`. Se fornecida, o watcher buscará ativamente os WABA IDs de `/internal/customers/active-waba-ids` deste serviço. Ex: `http://customer-manager.local`.
*   `MOCK_CUSTOMER_MANAGER_WABA_IDS`: (Opcional) Uma lista de WABA IDs separados por vírgula para usar em modo de teste, caso `CUSTOMER_MANAGER_URL` não seja fornecida ou para simular WABA IDs específicos. Ex: `waba_id_1,waba_id_2`.
*   `GUPSHUP_MOCK_MODE`: (Opcional) Se definido como `"true"`, o serviço não fará chamadas reais à API da Gupshup. Em vez disso, usará dados de template mockados gerados internamente para os WABA IDs configurados (seja via `MOCK_CUSTOMER_MANAGER_WABA_IDS` ou uma lista default). Útil para desenvolvimento e testes sem depender da API externa.
*   `PORT`: (Opcional) A porta em que o servidor HTTP irá rodar. O padrão é `8080`.

Exemplo de configuração completa:

```bash
export GUPSHUP_TOKEN="seu_token_real_da_gupshup"
export CUSTOMER_MANAGER_URL="http://localhost:3000" # Se estiver rodando o customer-manager localmente
# export MOCK_CUSTOMER_MANAGER_WABA_IDS="mock_waba1,mock_waba2" # Alternativa ou para complementar
# export GUPSHUP_MOCK_MODE="true" # Para testes offline
export PORT="8081" # Para rodar em uma porta diferente da padrão
```

**Nota:** O serviço utiliza `WABA_ID`s (WhatsApp Business Account ID) para identificar as contas cujos templates devem ser monitorados. Cada WABA ID funciona de forma análoga a um App ID no contexto da API de listagem de templates da Gupshup.

## Funcionalidades

O Notification Watcher Service desempenha as seguintes funções principais:

1.  **Busca de WABA IDs Ativos:**
    *   Se a variável `CUSTOMER_MANAGER_URL` estiver configurada, o serviço consulta periodicamente o endpoint `/internal/customers/active-waba-ids` para obter a lista de WABA IDs ativos.
    *   Caso contrário, ou se `MOCK_CUSTOMER_MANAGER_WABA_IDS` for fornecida, utiliza essa lista de WABA IDs.
2.  **Monitoramento de Templates da Gupshup:**
    *   Para cada WABA ID obtido, o serviço busca a lista de templates associados utilizando a API da Gupshup (endpoint no formato `https://api.gupshup.io/sm/api/v1/template/list/<WABA_ID>`).
    *   Se `GUPSHUP_MOCK_MODE` estiver `"true"`, utiliza dados mockados em vez de fazer chamadas reais à API.
3.  **Detecção de Mudanças de Categoria:**
    *   O serviço analisa os templates recebidos para identificar aqueles cuja categoria foi alterada. Isso é feito comparando o campo `:category` (nova categoria) com o campo `:oldCategory` (categoria anterior), se presente.
4.  **Filtragem de Templates:**
    *   Templates que possuem o status `:status "FAILED"` são desconsiderados no processo de verificação de mudança de categoria.
5.  **Registro (Logging):**
    *   Todas as mudanças de categoria detectadas são detalhadamente logadas no console, incluindo o WABA ID, ID do template, nome, categoria antiga e nova categoria.
    *   Logs de debug e de status da operação também são emitidos.
6.  **Exposição de API:**
    *   Disponibiliza um endpoint HTTP para consultar os templates que tiveram suas categorias alteradas (veja a seção "API Endpoints").

O ciclo de verificação ocorre a cada 10 minutos, após um atraso inicial de 30 segundos na primeira execução do watcher.

## API Endpoints

O serviço expõe os seguintes endpoints HTTP:

### 1. Status do Serviço

*   **Endpoint:** `GET /`
*   **Descrição:** Retorna uma mensagem simples indicando que o serviço está no ar.
*   **Resposta Exemplo (Status 200 OK):**
    ```text
    Serviço Notification Watcher está no ar.
    ```

### 2. Templates com Categoria Alterada

*   **Endpoint:** `GET /changed-templates`
*   **Descrição:** Retorna uma lista em formato JSON contendo todos os templates que foram detectados com uma mudança de categoria desde a última vez que o watcher armazenou os resultados. O watcher atualiza esta lista a cada ciclo de verificação (10 minutos).
*   **Exemplo de Requisição (usando `curl`):**
    ```bash
    curl http://localhost:8080/changed-templates
    ```
    (Substitua `localhost:8080` pelo endereço e porta corretos se o serviço não estiver rodando localmente com a porta padrão).
*   **Exemplo de Resposta JSON (Status 200 OK):**
    ```json
    [
      {
        "id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
        "elementName": "nome_do_template_exemplo",
        "category": "MARKETING",
        "oldCategory": "UTILITY",
        "wabaId": "seu_waba_id",
        "status": "APPROVED",
        "language": "pt_BR"
        // Outros campos originais do template (como `data`, `type`, etc.)
        // também podem estar presentes, dependendo da resposta da API Gupshup.
      },
      {
        "id": "yyyyyyyy-yyyy-yyyy-yyyy-yyyyyyyyyyyy",
        "elementName": "outro_template_alterado",
        "category": "AUTHENTICATION",
        "oldCategory": "MARKETING",
        "wabaId": "outro_waba_id",
        "status": "APPROVED",
        "language": "en_US"
      }
      // ... mais templates se houver
    ]
    ```
    Se nenhum template com mudança de categoria for encontrado ou armazenado, a resposta será uma lista JSON vazia `[]`.

## Melhorias Recentes

*   **Correção na Detecção de Mudança de Categoria (Outubro 2023):** A lógica para identificar templates com categorias alteradas foi refinada. Anteriormente, poderia haver inconsistências na forma como os campos dos templates eram acessados (strings vs. keywords). O acesso foi padronizado para usar keywords do Clojure (ex: `:oldCategory`, `:status`), o que torna a detecção de mudanças mais robusta e alinhada com a forma como os dados são tipicamente processados após a leitura de respostas JSON. Isso garante que os filtros para `status` "FAILED" e a presença de `oldCategory` funcionem de maneira confiável.

## Como Rodar

Para iniciar o serviço, execute o seguinte comando na raiz do projeto (dentro do diretório `notification-watcher` se você estiver na raiz do monorepo, caso contrário, navegue para `notification-watcher/` primeiro):

```bash
lein run
```

Ao executar este comando:

1.  O **servidor HTTP** será iniciado (por padrão na porta `8080`, a menos que a variável de ambiente `PORT` seja especificada). Este servidor disponibiliza os [API Endpoints](#api-endpoints) documentados acima.
2.  O **watcher de templates** começará a rodar em uma thread de background.
    *   Ele fará uma primeira verificação de templates após um atraso inicial de 30 segundos.
    *   Subsequentemente, o ciclo de verificação de templates ocorrerá a cada 10 minutos.
    *   Todas as atividades do watcher, incluindo templates com categorias alteradas, serão logadas no console.

O serviço continuará rodando, servindo as requisições HTTP e executando o watcher em background, até que seja interrompido manualmente (ex: `Ctrl+C` no terminal).
