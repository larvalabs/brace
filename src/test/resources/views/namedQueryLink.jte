@import com.larvalabs.brace.Url
@param int id

<a href="${Url.to("posts.show", id, Url.query("q", "red hat", "page", 2))}">Posts</a>
