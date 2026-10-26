@param gg.jte.Content csrfField = null
@param String csrfToken = null
@param java.util.Map<String, String> flash = null
<meta name="csrf-token" content="${csrfToken}">
notice=${flash != null && flash.get("notice") != null ? flash.get("notice") : "none"}
<form method="post" action="/cached-form">${csrfField}<button>Save</button></form>
