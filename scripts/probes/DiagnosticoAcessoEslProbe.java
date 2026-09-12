import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import feign.*;
import tools.jackson.databind.ObjectMapper;
import com.example.satelite.utils.CteXmlValidator;

/** Consultas documentais isoladas REST/GraphQL/SOAP/SFTP, sem Spring, envios ou escrita SQL. */
public final class DiagnosticoAcessoEslProbe {
    interface Api {
        @RequestLine("GET {path}")
        @Headers({"Accept: application/json", "Authorization: {auth}"})
        Response get(@Param("path") String path, @QueryMap Map<String,Object> query, @Param("auth") String auth);
        @RequestLine("POST /graphql")
        @Headers({"Content-Type: application/json", "Accept: application/json", "Authorization: {auth}"})
        Response schema(@Param("auth") String auth, String body);
    }
    static final ObjectMapper JSON = new ObjectMapper();
    static Map<String,String> env(Path arquivo) throws Exception {
        Map<String,String> values = new HashMap<>();
        for (String line : Files.readAllLines(arquivo)) {
            if(line.startsWith("\uFEFF")) line=line.substring(1);
            int at=line.indexOf('=');
            if(at<1 || line.stripLeading().startsWith("#")) continue;
            String value=line.substring(at+1).trim();
            if(value.length()>1 && ((value.startsWith("\"")&&value.endsWith("\""))||(value.startsWith("'")&&value.endsWith("'"))))
                value=value.substring(1,value.length()-1);
            values.put(line.substring(0,at).trim(),value);
        }
        return values;
    }
    static String seguro(String text, Map<String,String> env) {
        String s=String.valueOf(text);
        for(String v:env.values()) if(v!=null&&v.length()>=8) s=s.replace(v,"[configuracao]");
        s=s.replaceAll("[0-9]{44}","[chave]").replaceAll("(?i)[a-f0-9]{32,}","[identificador]");
        return s.substring(0,Math.min(500,s.length()));
    }
    public static void main(String[] args) throws Exception {
        Map<String,String> env=env(Path.of(".env"));
        if ("sftp".equals(args[0])) { inventariarPastasSftp(env,Path.of(args[1]));return; }
        if (Set.of("soap","soap_detalhe").contains(args[0])) { consultarDestino(env,Long.parseLong(args[1]),Path.of(args[2]),"soap_detalhe".equals(args[0]));return; }
        String operacao=args[0], chaveToken=args[1];
        if(!Set.of("xml","comprovante","ocorrencias","schema","schema_tipos","cte_graphql","frete_graphql").contains(operacao)) throw new IllegalArgumentException("OPERACAO_INVALIDA");
        if(!Set.of("RODOGARCIA_MASTER_API_REST","RODOGARCIA_TOKEN_VEDACIT","RODOGARCIA_TOKEN_VEDACIT_COMPROVANTE","API_GRAPHQL_TOKEN").contains(chaveToken))
            throw new IllegalArgumentException("CREDENCIAL_FORA_DO_ESCOPO");
        if("API_GRAPHQL_TOKEN".equals(chaveToken)) {
            var origem=env(Path.of("../etl-dash/etl-extracao-dados/.env"));
            if(!Objects.equals(origem.get("API_BASE_URL"),env.get("RODOGARCIA_API_BASE_URL")))
                throw new IllegalStateException("ORIGEM_DIVERGENTE");
            env.put(chaveToken,origem.get(chaveToken));
        }
        String token=env.get(chaveToken);
        if(token==null||token.isBlank()) throw new IllegalStateException("CREDENCIAL_AUSENTE");
        String esquema=args.length>4?args[4]:"Bearer";
        if(!Set.of("Bearer","Token").contains(esquema)) throw new IllegalArgumentException("ESQUEMA_INVALIDO");
        String authorization="Token".equals(esquema)?"Token token=\""+token+"\"":"Bearer "+token;
        long id=Long.parseLong(args[2]);
        var amostras=JSON.readTree(Path.of("target/rastreio-acesso-20260912/amostras.json").toFile());
        var amostra=amostras.valueStream().filter(n->n.path("id").asLong()==id).findFirst().orElseThrow();
        String cte=amostra.path("chave_cte").asText(), nfe=amostra.path("chave_nfe").asText();
        String referencia="";
        if("frete_graphql".equals(operacao)&&!nfe.matches("[0-9]{44}")) {
            var nome=java.util.regex.Pattern.compile("(?:^|/)([0-9]+)__([0-9]{44})\\.(?i:jpg|jpeg|png|jfif|pdf)$").matcher(amostra.path("canhoto_referencia").asText());
            if(nome.find()){referencia=nome.group(1);nfe=nome.group(2);}
        }
        if((!"frete_graphql".equals(operacao)&&!cte.matches("[0-9]{44}"))||!nfe.matches("[0-9]{44}")) throw new IllegalArgumentException("IDENTIDADE_INVALIDA");
        String path=switch(operacao) {case "xml" -> env.getOrDefault("RODOGARCIA_CTE_XML_PATH","/api/ctes");
            case "comprovante" -> "/api/freight_delivery_receipts";
            default -> env.getOrDefault("RODOGARCIA_CUSTOMER_OCCURRENCES_PATH","/api/customer/invoice_occurrences");};
        if(args.length>5 && Set.of("xml","comprovante").contains(operacao)) {
            if(!Set.of("/api/customer/ctes","/api/customer/freight_ctes","/api/customer/freight_delivery_receipts").contains(args[5])) throw new IllegalArgumentException("ROTA_INVALIDA");
            path=args[5];
        }
        Map<String,Object> query=switch(operacao) {case "xml" -> Map.of("key",cte); case "comprovante" -> Map.of("cte_key",cte);
            default -> Map.of("invoice_key",nfe,"occurrence_code",1);};
        var api=Feign.builder().retryer(Retryer.NEVER_RETRY).options(new Request.Options(10,TimeUnit.SECONDS,25,TimeUnit.SECONDS,false))
                .target(Api.class,env.get("RODOGARCIA_API_BASE_URL"));
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("momento",java.time.OffsetDateTime.now().toString());out.put("operacao",operacao);out.put("credencial",chaveToken);out.put("esquema",esquema);out.put("log_id",id);
        long inicio=System.nanoTime();
        String schemaQuery="query { __schema { queryType { name fields { name args { name type { kind name ofType { kind name } } } type { kind name ofType { kind name } } } } } }";
        if("schema_tipos".equals(operacao)) {
            String[] tipos=args[5].split(",");
            if(tipos.length>6) throw new IllegalArgumentException("MUITOS_TIPOS");
            StringBuilder q=new StringBuilder("query {");
            for(int i=0;i<tipos.length;i++) {
                if(!tipos[i].matches("[A-Za-z][A-Za-z0-9_]{0,80}")) throw new IllegalArgumentException("TIPO_INVALIDO");
                q.append(" t").append(i).append(":__type(name:\"").append(tipos[i]).append("\") { name kind inputFields { name type { kind name ofType { kind name ofType { kind name } } } } fields { name args { name type { kind name ofType { kind name } } } type { kind name ofType { kind name ofType { kind name } } } } }");
            }
            schemaQuery=q.append(" }").toString();
        }
        Map<String,Object> graphqlBody=Map.of("query",schemaQuery);
        if("cte_graphql".equals(operacao)) graphqlBody=Map.of("query","query($key:String!){cte(params:{key:$key},first:2){nodes{id key active status pdfServiceUrl freight{id withDeliveryReceipt freightCte{docEBuilderId}}}}}","variables",Map.of("key",cte));
        if("frete_graphql".equals(operacao)) graphqlBody=Map.of("query","query($key:String!){freight(params:{invoiceKey:$key},first:20){nodes{id corporationSequenceNumber withDeliveryReceipt cte{key status active} freightInvoices{invoice{key}}} pageInfo{hasNextPage}}}","variables",Map.of("key",nfe));
        try(Response response=operacao.startsWith("schema")||operacao.endsWith("_graphql")?api.schema(authorization,JSON.writeValueAsString(graphqlBody)):api.get(path,query,authorization)) {
            out.put("http",response.status());
            response.headers().forEach((k,v)->{if(Set.of("content-type","www-authenticate","location","server").contains(k.toLowerCase(Locale.ROOT)))
                out.put(k.toLowerCase(Locale.ROOT),seguro(String.join(",",v),env));});
            byte[] body=response.body()==null?new byte[0]:response.body().asInputStream().readNBytes(2_000_001);
            out.put("bytes",body.length);
            if(body.length>2_000_000) throw new IllegalStateException("RESPOSTA_EXCEDE_LIMITE");
            try {
                var root=JSON.readTree(body);
                out.put("campos_raiz",root.properties().stream().map(Map.Entry::getKey).toList());
                if(response.status()!=200) {
                    out.put("erro",seguro(root.toString(),env));
                } else {
                    var data=root.path("data");out.put("registros",data.isArray()?data.size():0);
                    if(data.isArray()&&!data.isEmpty())out.put("campos_primeiro",data.path(0).properties().stream().map(Map.Entry::getKey).toList());
                    if("comprovante".equals(operacao) && data.isArray()) {
                        out.put("comprovantes",data.valueStream().map(item -> Map.of(
                            "campos_frete",item.path("freight").properties().stream().map(Map.Entry::getKey).toList(),
                            "cte_exato",cte.equals(item.path("freight").path("cte_key").asText()),
                            "imagem_presente",!item.path("image_url").asText("").isBlank())).toList());
                        var dto=JSON.treeToValue(root,com.example.satelite.dto.rodogarcia.ComprovanteEslDTO.class);
                        out.put("dto_compativel",dto!=null&&dto.data()!=null&&dto.data().size()==data.size());
                    }
                    if("schema".equals(operacao)) {
                        out.put("query_type",data.path("__schema").path("queryType").path("name").asText());
                        out.put("schema_documental",data.path("__schema").path("queryType").path("fields").valueStream()
                            .filter(n->n.path("name").asText().matches("(?i).*(cte|freight|invoice|receipt|xml).*"))
                            .map(n->JSON.convertValue(n,Map.class)).toList());
                        if(root.has("errors")) out.put("erros_schema",seguro(root.path("errors").toString(),env));
                    }
                    if("schema_tipos".equals(operacao)) {
                        out.put("tipos",JSON.convertValue(data,Map.class));
                        if(root.has("errors")) out.put("erros_schema",seguro(root.path("errors").toString(),env));
                    }
                    if("cte_graphql".equals(operacao)) {
                        List<Map<String,Object>> documentos=new ArrayList<>();
                        for(var node:data.path("cte").path("nodes")) {
                            Map<String,Object> item=new LinkedHashMap<>();
                            item.put("id",node.path("id").asText());item.put("cte_exato",cte.equals(node.path("key").asText()));
                            item.put("ativo",node.path("active").asBoolean());item.put("status",node.path("status").asText());
                            item.put("comprovante_indicado",node.path("freight").path("withDeliveryReceipt").asBoolean());
                            item.put("doc_e_id_presente",!node.path("freight").path("freightCte").path("docEBuilderId").asText("").isBlank());
                            String pdf=node.path("pdfServiceUrl").asText("");
                            if(!pdf.isBlank()) {var uri=java.net.URI.create(pdf);item.put("pdf_host",uri.getHost());item.put("pdf_caminho_formato",seguro(uri.getPath(),env).replaceAll("(?i)[a-z0-9_-]{24,}","[id]"));}
                            documentos.add(item);
                        }
                        out.put("documentos",documentos);
                        if(root.has("errors")) out.put("erros_graphql",seguro(root.path("errors").toString(),env));
                    }
                    if("frete_graphql".equals(operacao)) {
                        List<Map<String,Object>> documentos=new ArrayList<>();
                        for(var node:data.path("freight").path("nodes")) {
                            Map<String,Object> item=new LinkedHashMap<>();
                            item.put("freight_id",node.path("id").asText());
                            item.put("referencia_igual_freight",referencia.equals(node.path("id").asText()));
                            item.put("referencia_igual_numero",referencia.equals(node.path("corporationSequenceNumber").asText()));
                            boolean nfeExata=false;for(var nf:node.path("freightInvoices")) if(nfe.equals(nf.path("invoice").path("key").asText()))nfeExata=true;
                            item.put("nfe_exata",nfeExata);String chave=node.path("cte").path("key").asText("");
                            item.put("cte_presente",chave.matches("[0-9]{44}"));item.put("cte_numero",chave.length()==44?chave.substring(25,34):"");
                            item.put("cte_status",node.path("cte").path("status").asText());item.put("cte_ativo",node.path("cte").path("active").asBoolean());
                            item.put("comprovante_indicado",node.path("withDeliveryReceipt").asBoolean());documentos.add(item);
                        }
                        out.put("documentos",documentos);out.put("ha_mais",data.path("freight").path("pageInfo").path("hasNextPage").asBoolean());
                        if(root.has("errors")) out.put("erros_graphql",seguro(root.path("errors").toString(),env));
                    }
                    int xmls=0,exatos=0,imagens=0;
                    for(var item:data) {
                        String xml=item.path("cte").path("xml").asText("");
                        if(!xml.isBlank()){xmls++;if(CteXmlValidator.corresponde(xml.getBytes(StandardCharsets.UTF_8),cte,nfe))exatos++;}
                        if(!item.path("image_url").asText("").isBlank()) imagens++;
                    }
                    out.put("xmls",xmls);out.put("xmls_correlacionados",exatos);out.put("imagens",imagens);
                }
            } catch(Exception e) {out.put("parse_tipo",e.getClass().getSimpleName());out.put("html",new String(body,StandardCharsets.UTF_8).contains("<html"));}
        } catch(Exception e) {out.put("erro_tipo",e.getClass().getSimpleName());}
        out.put("duracao_ms",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-inicio));
        JSON.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[3]).toFile(),out);
        System.out.println(JSON.writeValueAsString(out));
    }

    static void inventariarPastasSftp(Map<String,String> env,Path saida) throws Exception {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.OFF);
        String base=env.get("SFTP_RODOGARCIA_BASE_PATH"),cliente=env.get("SFTP_RODOGARCIA_CLIENT_PATH");
        cliente=com.example.satelite.services.origem.sftp.vedacit.VedacitSftpPathPolicy.validarDiretorioCliente(base,cliente);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("momento",java.time.OffsetDateTime.now().toString());
        List<Map<String,Object>> pastas=new ArrayList<>();
        try(var ssh=new net.schmizz.sshj.SSHClient()) {
            ssh.addHostKeyVerifier(net.schmizz.sshj.transport.verification.FingerprintVerifier.getInstance(env.get("SFTP_RODOGARCIA_HOST_KEY_SHA256")));
            ssh.setConnectTimeout(10000);ssh.setTimeout(25000);
            ssh.connect(env.get("SFTP_RODOGARCIA_HOST"),Integer.parseInt(env.getOrDefault("SFTP_RODOGARCIA_PORT","22")));
            ssh.authPassword(env.get("SFTP_RODOGARCIA_USERNAME"),env.get("SFTP_RODOGARCIA_PASSWORD"));
            try(var sftp=ssh.newSFTPClient()) {
                if(!cliente.equals(sftp.canonicalize(cliente)))throw new IllegalStateException("CAMINHO_CLIENTE_DIVERGENTE");
                var fila=new ArrayDeque<String>();fila.add(cliente);
                while(!fila.isEmpty() && pastas.size()<12) {
                    String pasta=fila.remove();
                    if(sftp.lstat(pasta).getType()!=net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY)continue;
                    var arquivos=sftp.ls(pasta);
                    Map<String,Object> item=new LinkedHashMap<>();
                    item.put("pasta",pasta.equals(cliente)?".":pasta.substring(cliente.length()+1));
                    item.put("arquivos",arquivos.stream().filter(f->f.getAttributes().getType()==net.schmizz.sshj.sftp.FileMode.Type.REGULAR).count());
                    item.put("xmls",arquivos.stream().filter(f->f.getName().toLowerCase(Locale.ROOT).endsWith(".xml")).count());
                    List<String> subpastas=new ArrayList<>();
                    for(var f:arquivos) if(f.getAttributes().getType()==net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY
                            && f.getName().matches("[A-Za-z0-9_-]{1,80}")) {
                        subpastas.add(f.getName());
                        if(pasta.substring(cliente.length()).chars().filter(c->c=='/').count()<2)fila.add(pasta+"/"+f.getName());
                    }
                    item.put("subpastas",subpastas);pastas.add(item);
                }
                out.put("conexao","OK");out.put("pastas",pastas);out.put("fila_restante",fila.size());
            }
        } catch(Exception e) {out.put("erro_tipo",e.getClass().getSimpleName());}
        JSON.writerWithDefaultPrettyPrinter().writeValue(saida.toFile(),out);
        System.out.println(JSON.writeValueAsString(out));
    }

    static void consultarDestino(Map<String,String> env,long id,Path saida,boolean detalhe) throws Exception {
        ((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).setLevel(ch.qos.logback.classic.Level.OFF);
        var amostras=JSON.readTree(Path.of("target/rastreio-acesso-20260912/amostras.json").toFile());
        var amostra=amostras.valueStream().filter(n->n.path("id").asLong()==id).findFirst().orElseThrow();
        String cte=amostra.path("chave_cte").asText(),nfe=amostra.path("chave_nfe").asText();
        if(!cte.matches("[0-9]{44}")||!nfe.matches("[0-9]{44}"))throw new IllegalArgumentException("IDENTIDADE_INVALIDA");
        var integracao=new com.example.satelite.services.vedacit.VedacitIntegrationService(null,null,null);
        for(var e:Map.<String,Object>of("vedacitToken",env.get("VEDACIT_API_TOKEN"),
                "vedacitApiBaseUrl",env.get("VEDACIT_API_BASE_URL"),"soapConnectTimeoutMs",10000,
                "soapReadTimeoutMs",25000,"soapInvocationTimeoutMs",30000).entrySet()) {
            var campo=integracao.getClass().getDeclaredField(e.getKey());campo.setAccessible(true);campo.set(integracao,e.getValue());
        }
        var consulta=new com.example.satelite.services.vedacit.VedacitConsultaReconciliacaoService(integracao);
        var out=new LinkedHashMap<String,Object>();out.put("momento",java.time.OffsetDateTime.now().toString());out.put("log_id",id);
        if(!detalhe) {out.put("xml",consulta.consultarXml(cte));out.put("comprovante",consulta.consultarComprovante(nfe));}
        else {
            for(String operacao:List.of("xml","comprovante")) {
                try {
                    var metodo=integracao.getClass().getDeclaredMethod("xml".equals(operacao)?"criarPortaCte":"criarPortaNFe");metodo.setAccessible(true);
                    Object porta=metodo.invoke(integracao);
                    if("xml".equals(operacao)) {
                        var r=((com.example.satelite.vedacit.cte.ICTe)porta).buscarCTePorChave(cte);
                        out.put(operacao,Map.of("status",String.valueOf(r.isStatus()),"mensagem",seguro(r.getMensagem()==null?null:r.getMensagem().getValue(),env)));
                    } else {
                        var r=((com.example.satelite.vedacit.nfe.INFe)porta).buscarCanhotoPorChaveNFe(nfe);
                        out.put(operacao,Map.of("status",String.valueOf(r.isStatus()),"mensagem",seguro(r.getMensagem()==null?null:r.getMensagem().getValue(),env)));
                    }
                } catch(Exception e) {out.put(operacao,Map.of("erro_tipo",e.getClass().getSimpleName(),"mensagem",seguro(e.getMessage(),env)));}
            }
        }
        out.put("consultas",2);out.put("envios",0);
        JSON.writerWithDefaultPrettyPrinter().writeValue(saida.toFile(),out);
        System.out.println(JSON.writeValueAsString(out));
    }
}
