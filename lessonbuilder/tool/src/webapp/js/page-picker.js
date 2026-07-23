
// Cache the show items and hide items links
var showItems = $('#show-items');
var hideItems = $('#hide-items');

$(function() {

document.querySelectorAll('a.itemListToggle').forEach((toggle) => {
    const list = toggle.closest('li')?.querySelector(':scope > .itemListContainer > .itemList');
    if (list?.id) {
        toggle.setAttribute('aria-controls', list.id);
    }

    toggle.addEventListener('click', (event) => {
        event.preventDefault();

        if (!list) {
            return;
        }

        const willShow = window.getComputedStyle(list).display === 'none';
        list.style.display = willShow ? 'block' : 'none';
        toggle.setAttribute('aria-expanded', willShow ? 'true' : 'false');

        if (typeof window.frameElement !== 'undefined') {
            setMainFrameHeight(window.frameElement.id);
        }
    });
});

$('#show-items').on('click', function (e) {

    showItems.hide();
    hideItems.show();
    $('.itemList').show();
    document.querySelectorAll('a.itemListToggle').forEach((toggle) => {
        toggle.setAttribute('aria-expanded', 'true');
    });
    if(typeof window.frameElement !== 'undefined') {
        setMainFrameHeight(window.frameElement.id);
    }
    return false;
});

$('#hide-items').on('click', function (e) {

    showItems.show();
    hideItems.hide();
    $('.itemList').hide();
    document.querySelectorAll('a.itemListToggle').forEach((toggle) => {
        toggle.setAttribute('aria-expanded', 'false');
    });
    if(typeof window.frameElement !== 'undefined') {
        setMainFrameHeight(window.frameElement.id);
    }
    return false;
});

$("#chooseall").change(function(){
	$(".deletebox").prop('checked', $("#chooseall").prop('checked'));
});

});

    
