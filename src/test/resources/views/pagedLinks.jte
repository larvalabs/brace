@import com.larvalabs.brace.Paged
@param Paged<Integer> numbers

<ul>@for(var n : numbers.items())<li>${n}</li>@endfor</ul>
<nav>
@if(numbers.hasPrev())<a rel="prev" href="${numbers.prevUrl()}">Prev</a>@endif
@for(var link : numbers.links())@if(link.gap())<span>…</span>@elseif(link.current())<b>${link.label()}</b>@else<a href="${link.url()}">${link.label()}</a>@endif
@endfor
@if(numbers.hasNext())<a rel="next" href="${numbers.nextUrl()}">Next</a>@endif
</nav>
