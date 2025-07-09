```markdown
# Exemplo de Fluxo Prático: A Jornada de uma Notificação ao Sr. Silva

**ID do Documento:** ARQ-SNCT-20250710-FLUXO-V1.0
**Data:** 10 de Julho de 2025
**Versão:** 1.0
**Autor:** Gemini, Arquiteto de Sistemas

**Propósito deste Documento:** Ilustrar, através de uma história de usuário detalhada, o funcionamento ponta-a-ponta do Sistema de Notificação de Mudança de Categoria de Templates (SNCT). Este exemplo prático visa complementar o relatório técnico principal, oferecendo uma perspectiva mais narrativa de como os microsserviços colaboram para entregar a notificação final ao cliente.

-----

## Personagens e Cenário

*   **Cliente Final:** Sr. Silva, gestor de comunicação da empresa "Flores Online Ltda."
*   **Identificador do Cliente (WABA ID):** `WABA_FLORES_123`
*   **Número de Contato do Sr. Silva:** `+5511987654321` (devidamente cadastrado no sistema)
*   **Template Específico:** Nome do template `pedido_confirmado`, ID técnico `tpl_pedido_conf_xyz`.
*   **Evento Desencadeador:** A Meta (provedora do WhatsApp) altera a categoria do template `tpl_pedido_conf_xyz` de "MARKETING" para "UTILITY".

**Pré-condições do Sistema:**
1.  A empresa "Flores Online Ltda." (`WABA_FLORES_123`) está registrada e configurada como ativa no `customer-manager-service`. O número de telefone do Sr. Silva está associado como o contato principal para notificações.
2.  Os três microsserviços (`customer-manager-service`, `notification-watcher`, `sms-notifier`) estão operacionais e configurados corretamente em seus respectivos ambientes.
3.  As APIs externas (Gupshup, provedor de SMS) estão acessíveis.

-----

## A Jornada da Notificação: Passo a Passo Detalhado

A seguir, descrevemos a sequência de eventos e interações entre os serviços que culminam no envio do SMS para o Sr. Silva.

**Fase 1: O Despertar do Guardião (`notification-watcher`)**

1.  **Início do Ciclo de Verificação:**
    *   O `notification-watcher` é ativado por seu agendador interno (ex: a cada X minutos). Sua missão: descobrir se alguma categoria de template mudou para os clientes ativos.

2.  **Consulta de Clientes Ativos:**
    *   **`notification-watcher` -> `customer-manager-service`:**
        *   **Requisição:** `GET /internal/customers/active-waba-ids`
        *   O `notification-watcher` pergunta: "Olá `customer-manager-service`, por favor, me informe quais WABA IDs de clientes devo monitorar neste ciclo?"
    *   **`customer-manager-service` -> Banco de Dados de Clientes:**
        *   Consulta sua base de dados para buscar todos os clientes com `is_active = true`.
    *   **`customer-manager-service` -> `notification-watcher`:**
        *   **Resposta (JSON):** `{ "waba_ids": ["WABA_FLORES_123", "WABA_OUTROCLIENTE_456", ...] }`
        *   O `customer-manager-service` responde: "Certamente! Você deve verificar `WABA_FLORES_123`, `WABA_OUTROCLIENTE_456`, e os demais da lista."

**Fase 2: Investigação nos Provedores (`notification-watcher`)**

3.  **Coleta de Dados de Templates (Iteração por Cliente):**
    *   O `notification-watcher` inicia um loop para cada `wabaId` recebido. Focaremos em `WABA_FLORES_123`.
    *   **`notification-watcher` -> API da Gupshup:**
        *   **Requisição:** (Exemplo) `GET api.gupshup.io/sm/api/v1/templates?appId=WABA_FLORES_123` (com autenticação apropriada)
        *   Ele pergunta à Gupshup: "Gupshup, poderia me fornecer a lista atual e os detalhes de todos os templates associados ao `WABA_FLORES_123`?"
    *   **API da Gupshup -> `notification-watcher`:**
        *   **Resposta (JSON):** Uma lista de templates, onde o template `tpl_pedido_conf_xyz` agora consta com `"category": "UTILITY"`.
        ```json
        [
          // ... outros templates ...
          {
            "id": "tpl_pedido_conf_xyz",
            "elementName": "pedido_confirmado",
            "category": "UTILITY",
            "language": "pt_BR",
            "status": "APPROVED",
            // ... outros campos ...
          }
          // ... outros templates ...
        ]
        ```

4.  **Análise e Detecção da Mudança:**
    *   O `notification-watcher` compara o estado atual do template `tpl_pedido_conf_xyz` com o estado que ele conhecia anteriormente (seja por um cache interno simples ou por lógica de comparação).
    *   Ele identifica a discrepância: "Entendido. O template `tpl_pedido_conf_xyz` para `WABA_FLORES_123` antes era 'MARKETING' (ou não estava na categoria 'UTILITY') e agora é 'UTILITY'. Esta é uma mudança relevante!"

5.  **Publicação da Descoberta:**
    *   O `notification-watcher` prepara um evento/dado para ser consumido pelo `sms-notifier`.
    *   Internamente, ele atualiza a lista que será exposta em seu endpoint `/changed-templates`:
        ```json
        // Conteúdo que será retornado por GET /changed-templates
        [
          {
            "id": "tpl_pedido_conf_xyz",
            "elementName": "pedido_confirmado",
            "wabaId": "WABA_FLORES_123",
            "category": "UTILITY",
            "oldCategory": "MARKETING", // Inferida ou recuperada
            "language": "pt_BR",
            "status": "APPROVED"
          }
          // ... outras mudanças de outros clientes, se houver ...
        ]
        ```

**Fase 3: Ação e Notificação (`sms-notifier`)**

6.  **Busca por Novidades:**
    *   O `sms-notifier` é ativado por seu agendador interno.
    *   **`sms-notifier` -> `notification-watcher`:**
        *   **Requisição:** `GET /changed-templates`
        *   Ele pergunta: "`notification-watcher`, há alguma mudança de categoria de template para eu processar?"
    *   **`notification-watcher` -> `sms-notifier`:**
        *   **Resposta (JSON):** (Conteúdo do passo 5)
        *   Ele responde: "Sim! O template `tpl_pedido_conf_xyz` do cliente `WABA_FLORES_123` mudou de 'MARKETING' para 'UTILITY'."

7.  **Processamento da Mudança Específica:**
    *   O `sms-notifier` recebe a lista e itera sobre ela. Foco na mudança do `tpl_pedido_conf_xyz`.

8.  **Identificação do Destinatário:**
    *   O `sms-notifier` extrai `wabaId = "WABA_FLORES_123"`.
    *   **`sms-notifier` -> `customer-manager-service`:**
        *   **Requisição:** `GET /internal/customers/contact-info/WABA_FLORES_123`
        *   Ele pergunta: "`customer-manager-service`, qual é o número de telefone de contato para notificações do cliente `WABA_FLORES_123`?"
    *   **`customer-manager-service` -> Banco de Dados de Clientes:**
        *   Busca o cliente `WABA_FLORES_123`.
    *   **`customer-manager-service` -> `sms-notifier`:**
        *   **Resposta (JSON):** `{ "waba_id": "WABA_FLORES_123", "contact_phone": "+5511987654321" }`
        *   Ele responde: "O contato para `WABA_FLORES_123` é `+5511987654321`."

9.  **Garantia de Não Duplicidade (Idempotência):**
    *   O `sms-notifier` agora tem: Template ID (`tpl_pedido_conf_xyz`), Nova Categoria (`UTILITY`), WABA ID (`WABA_FLORES_123`).
    *   **`sms-notifier` -> Seu Banco de Dados (`sent_notifications`):**
        *   **Consulta SQL (Exemplo):** `SELECT 1 FROM sent_notifications WHERE template_id = 'tpl_pedido_conf_xyz' AND new_category = 'UTILITY' AND waba_id = 'WABA_FLORES_123';`
        *   Ele verifica: "Base de dados `sent_notifications`, eu já enviei um alerta sobre o template `tpl_pedido_conf_xyz` ter mudado para 'UTILITY' para o cliente `WABA_FLORES_123`?"
    *   **Banco de Dados (`sent_notifications`) -> `sms-notifier`:**
        *   **Resposta:** (Nenhum registro encontrado)
        *   A base responde: "Não, esta é a primeira vez que você me pergunta sobre essa combinação específica."

10. **Envio Efetivo do SMS:**
    *   O `sms-notifier` tem luz verde: a notificação é nova e o contato existe.
    *   **Formatação da Mensagem:**
        *   Ele constrói a mensagem: "Flores Online Ltda.: Informamos que a categoria do seu template WhatsApp 'pedido_confirmado' foi alterada pela Meta. Categoria anterior: MARKETING. Nova categoria: UTILITY."
    *   **`sms-notifier` -> API do Provedor de SMS (ex: Twilio):**
        *   **Requisição:** (Exemplo) `POST https://api.twilio.com/2010-04-01/Accounts/ACxxxx/Messages.json`
            *   **Payload:** `{ "To": "+5511987654321", "From": "YOUR_TWILIO_NUMBER_OR_SENDER_ID", "Body": "Flores Online Ltda.: Informamos que a categoria do seu template WhatsApp 'pedido_confirmado' foi alterada pela Meta. Categoria anterior: MARKETING. Nova categoria: UTILITY." }`
        *   Ele instrui: "Provedor de SMS, por favor, envie esta mensagem para o número `+5511987654321`."
    *   **API do Provedor de SMS -> `sms-notifier`:**
        *   **Resposta:** (Confirmação de que a mensagem foi aceita para envio, ex: `{"sid": "SMxxxx", "status": "queued"}`)

11. **Registro da Ação Concluída:**
    *   Assumindo que o provedor de SMS aceitou a mensagem.
    *   **`sms-notifier` -> Seu Banco de Dados (`sent_notifications`):**
        *   **Comando SQL (Exemplo):** `INSERT INTO sent_notifications (template_id, new_category, waba_id, sent_at) VALUES ('tpl_pedido_conf_xyz', 'UTILITY', 'WABA_FLORES_123', CURRENT_TIMESTAMP);`
        *   Ele registra: "Base de dados `sent_notifications`, anote aí: enviei a notificação para `tpl_pedido_conf_xyz` (mudança para 'UTILITY', cliente `WABA_FLORES_123`) agora mesmo."

**Fase 4: A Chegada da Notícia (Cliente Final)**

12. **Recebimento da Mensagem:**
    *   O celular do Sr. Silva, `+5511987654321`, recebe uma nova mensagem SMS.
    *   Ele visualiza:
        > "Flores Online Ltda.: Informamos que a categoria do seu template WhatsApp 'pedido_confirmado' foi alterada pela Meta. Categoria anterior: MARKETING. Nova categoria: UTILITY."

13. **Conscientização e Ação Potencial:**
    *   Sr. Silva agora está ciente da mudança de categoria. Ele pode verificar as implicações dessa alteração para as campanhas da "Flores Online Ltda.", ajustar orçamentos ou estratégias conforme necessário.

-----

**Conclusão da História:**

O Sr. Silva foi notificado com sucesso e em tempo hábil sobre uma mudança crítica em um de seus templates. Isso foi possível graças à orquestração dos três microsserviços, cada um executando sua parte especializada no processo: o `customer-manager-service` gerenciando os dados do cliente, o `notification-watcher` detectando a mudança no provedor externo, e o `sms-notifier` garantindo a entrega da mensagem de forma idempotente e registrando o evento.
```
