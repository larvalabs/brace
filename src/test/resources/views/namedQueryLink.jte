@import com.larvalabs.brace.Url
@import com.larvalabs.brace.PostsQuery
@param int id
<a href="${Url.to("users.posts", id, new PostsQuery("red hat", 2))}">posts</a>
