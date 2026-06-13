@import com.larvalabs.brace.testmodels.Post
@param Post post
!{ String missing = null; }
<h1>${post.title}</h1>
<p>${missing.length()}</p>
