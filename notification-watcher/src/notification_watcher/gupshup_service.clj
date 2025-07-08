(ns notification-watcher.gupshup-service
  (:require [clojure.spec.alpha :as s]
            [clj-http.client :as client]))

;; --- Definição da "Forma" dos Dados (Schema) ---
;; Esta é a definição formal do que consideramos um "template limpo".
(s/def ::id string?)
(s/def ::elementName string?)
(s/def ::status #{"APPROVED"}) ; Exige que o status seja exatamente "APPROVED"
(s/def ::data string?)

(s/def ::template (s/keys :req-un [::id ::elementName ::status ::data]))


;; --- Funções do Serviço ---

(defn- fetch-raw-data
  "Função privada responsável APENAS por fazer a chamada HTTP.
   É uma função 'impura' (com efeitos colaterais)."
  []
  (let [api-key (System/getenv "GUPSHUP_TOKEN")]
    (if-not api-key
      (println "ERRO: A variável de ambiente GUPSHUP_API_KEY não está definida.")
      (try
        (let [response (client/get "https://api.gupshup.io/sm/api/v1/template/list"
                                   {:headers          {:apikey api-key}
                                    :as               :json
                                    :throw-exceptions false ; Não lança exceção para erros http (4xx/5xx)
                                    :conn-timeout     5000  ; Timeout de conexão de 5 segundos
                                    :socket-timeout   5000}) ; Timeout de resposta de 5 segundos
              status (:status response)]
          (if (<= 200 status 299)
            (:body response)
            (do
              (println (str "ERRO: API da Gupshup retornou status " status ". Resposta: " (:body response)))
              nil))) ; Retorna nil se o status não for 2xx
        (catch Exception e
          (println (str "ERRO: Falha crítica na comunicação com a API da Gupshup: " (.getMessage e)))
          nil))))) ; Retorna nil em caso de falha de rede/DNS etc

(defn- parse-and-clean-templates
  "A Camada Anti-Corrupção em ação: filtra, traduz e valida.
   É uma função 'pura' (sem efeitos colaterais)."
  [raw-data]
  (->> (get-in raw-data [:templates] []) ; Pega a lista de templates de forma segura
       ;; 1. Filtra: só nos importamos com templates aprovados
       (filter #(= "APPROVED" (:status %)))

       ;; 2. Traduz/Mapeia para a nossa forma limpa, pegando só as chaves que queremos
       (map #(select-keys % [:id :elementName :status :data]))

       ;; 3. Valida (Opcional, mas recomendado): garante que os dados estão na forma correta
       (filter #(s/valid? ::template %))

       (doall))) ; Garante que a lazy sequence seja processada imediatamente

(defn get-approved-templates
  "A única função pública. Orquestra os passos e é o que o resto da app vai chamar."
  []
  (println "Buscando templates da Gupshup...")
  (if-let [raw-data (fetch-raw-data)]
    (do
      (println "Dados recebidos. Limpando e validando templates...")
      (let [approved-templates (parse-and-clean-templates raw-data)]
        (println (str (count approved-templates) " templates aprovados e prontos para uso."))
        approved-templates))
    [])) ; Retorna uma coleção vazia se a chamada à API falhar
