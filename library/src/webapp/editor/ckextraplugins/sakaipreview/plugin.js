﻿/**
 * @license Copyright (c) 2003-2016, CKSource - Frederico Knabben. All rights reserved.
 * For licensing, see LICENSE.md or http://ckeditor.com/license
 */

/**
 * @fileOverview Preview plugin.
 */

/**
 * This is a copy of the CKEditor 'preview' plugin but customized for use in
 * Sakai.  It adds all the page CSS to the preview HTML and also
 * displays the content in a modal dialog (if Bootstrap is included).
 */

( function() {
	var pluginPath;

	var previewCmd = { modes: { wysiwyg: 1, source: 1 },
		canUndo: false,
		readOnly: 1,
		exec: function( editor ) {
			var sHTML,
				config = editor.config,
				baseTag = config.baseHref ? '<base href="' + config.baseHref + '"/>' : '',
				eventData;

			if ( config.fullPage )
				sHTML = editor.getData().replace( /<head>/, '$&' + baseTag ).replace( /[^>]*(?=<\/title>)/, '$& &mdash; ' + editor.lang.sakaipreview.preview );
			else {
				var bodyHtml = '<body ',
					body = editor.document && editor.document.getBody();

				if ( body ) {
					if ( body.getAttribute( 'id' ) )
						bodyHtml += 'id="' + body.getAttribute( 'id' ) + '" ';
					if ( body.getAttribute( 'class' ) )
						bodyHtml += 'class="' + body.getAttribute( 'class' ) + '" ';
				}

				bodyHtml += '>';

        var sakaiStylesheets = "";
        $PBJQ("link[rel='stylesheet']").each(function(){
          sakaiStylesheets = sakaiStylesheets + this.outerHTML;
        });

        var mathjaxIncludes = "";
        if ($PBJQ("script[type*='mathjax-config']").length > 0) {
          mathjaxIncludes = mathjaxIncludes + $PBJQ("script[type*='mathjax-config']")[0].outerHTML.replace(";executed=true", "");
          $PBJQ("script[src*='MathJax.js'], script[src*='/mathjax/']").each(function() {
            mathjaxIncludes = mathjaxIncludes + this.outerHTML;
          });
        }

        var baseURL = location.protocol + "//" + location.hostname;
        if (location.port != "") {
          baseURL = baseURL + ":" + location.port
        }

        function getPreviewContentHTML(contentToPreview) {
            let darkThemeEnabled = document.firstElementChild.classList.contains('sakaiUserTheme-dark') ? 'sakaiUserTheme-dark' : '';

            return editor.config.docType + '<html dir="' + editor.config.contentsLangDirection + '" class="' + darkThemeEnabled + '">' +
                    '<head>' +
                        baseTag +
                        '<title>' + editor.lang.sakaipreview.preview + '</title>' +
                        '<base href="'+ baseURL +'"/>' +
                        CKEDITOR.tools.buildStyleHtml( editor.config.contentsCss ) +
                        sakaiStylesheets +
                        mathjaxIncludes +
                    '</head>'+
                    bodyHtml +
                    contentToPreview +
                    '</body></html>';
        }

        function getIframe(html) {
            var $iframe = $PBJQ('<iframe>');
            $iframe.attr('srcdoc', html);
            $iframe.attr('frameborder', '0');
            $iframe.attr('width', '100%');
            $iframe.attr('height', $PBJQ(window).height() - 200 + "px");
            return $iframe;
        }

        var previewHighlightedOnly = false;
        function getEditorContent() {
          //Available since CKEditor 4.5.0 to get selected Html. True to return html.
          var selectedHtml = editor.getSelectedHtml(true);

          if (selectedHtml) {
            previewHighlightedOnly = true;
            return selectedHtml;
          }

          return editor.editable().getHtml();
        }

        sHTML = getPreviewContentHTML(getEditorContent());
			}

			var iWidth = 640,
				// 800 * 0.8,
				iHeight = 420,
				// 600 * 0.7,
				iLeft = 80; // (800 - 0.8 * 800) /2 = 800 * 0.1.
			try {
				var screen = window.screen;
				iWidth = Math.round( screen.width * 0.8 );
				iHeight = Math.round( screen.height * 0.7 );
				iLeft = Math.round( screen.width * 0.1 );
			} catch ( e ) {}

			// (#9907) Allow data manipulation before preview is displayed.
			// Also don't open the preview window when event cancelled.
			if ( editor.fire( 'contentPreview', eventData = { dataValue: sHTML } ) === false )
				return false;

            // Let's try and open a modal!
            if ($PBJQ && typeof window.bootstrap !== 'undefined' && window.bootstrap.Modal) {
                var modalId = 'ckeditorPreview' + Date.now();
                var $modal = $PBJQ('<div class="modal fade" id="' + modalId + '" tabindex="-1" aria-hidden="true">'+
                                     '<div class="modal-dialog modal-xl">'+
                                       '<div class="modal-content">'+
                                         '<div class="modal-header">'+
                                           '<h5 class="modal-title">'+editor.lang.sakaipreview.preview+'</h5>'+
                                           '<button type="button" class="btn btn-transparent text-white sakaipreview-print ms-2 me-auto">'+
                                             '<span class="bi bi-printer" aria-hidden="true"></span>'+
                                           '</button>'+
                                           '<button type="button" class="btn-close ms-0 me-1" data-bs-dismiss="modal" aria-label="Close"></button>'+
                                         '</div>'+
                                         '<div class="modal-body p-0"></div>'+
                                       '</div>'+
                                     '</div>'+
                                   '</div>');

                if (previewHighlightedOnly) {
                    var $message = $PBJQ("<div>").addClass("alert alert-info").
                                              text(editor.lang.sakaipreview.previewFullPreamble).
                                              css("margin", "20px 0 0");

                    var $previewAll =  $PBJQ("<a>").attr("href", "javascript:void(0);").
                                              addClass("btn btn-xs btn-default").
                                              text(editor.lang.sakaipreview.previewFullButton);

                    $previewAll.on("click", function() {
                        previewHighlightedOnly = false;
                        $modal.find(".modal-body").html(getIframe(getPreviewContentHTML(getEditorContent())));
                        $message.remove();
                    });

                    $message.append($previewAll);

                    $modal.find(".modal-header").append($message);
                }

                $PBJQ(document.body).append($modal);

                $modal.find(".sakaipreview-print").on("click", function() {
                  $modal.find(".sakaipreview-print").prop("disabled", true);
                  // we need to get the editor HTML again and tack on some
                  // javascript to do the printing for us!
                  var html = getPreviewContentHTML(getEditorContent());
                  if (typeof MathJax != "undefined") {
                    // print after MathJax has finished rendering
                    html = html + "<script type='text/javascript'>"+
                                    "MathJax.Hub.Queue(function () {"+
                                      "window.print();"+
                                    "});"+
                                  "</script>";
                    } else {
                      // just print!
                      html = html + "<script type='text/javascript'>"+
                                      "window.print();"+
                                    "</script>";
                    }
                  $modal.find(".modal-body").html(getIframe(html));

                  setTimeout(function() {
                    $modal.find(".sakaipreview-print").prop("disabled", false);
                  }, 3000);
                });

                var modalInstance = new window.bootstrap.Modal($modal[0], {
                    backdrop: true,
                    keyboard: true
                });
                modalInstance.show();

                $modal.find(".modal-body").append(getIframe(sHTML));
                
                $modal[0].addEventListener('hidden.bs.modal', function() {
                    $modal.remove();
                });

                return true;
            }

			var sOpenUrl = '',
				ieLocation;

			if ( CKEDITOR.env.ie ) {
				window._cke_htmlToLoad = eventData.dataValue;
				ieLocation = 'javascript:void( (function(){' + // jshint ignore:line
					'document.open();' +
					// Support for custom document.domain.
					// Strip comments and replace parent with window.opener in the function body.
					( '(' + CKEDITOR.tools.fixDomain + ')();' ).replace( /\/\/.*?\n/g, '' ).replace( /parent\./g, 'window.opener.' ) +
					'document.write( window.opener._cke_htmlToLoad );' +
					'document.close();' +
					'window.opener._cke_htmlToLoad = null;' +
				'})() )';
				// For IE we should use window.location rather than setting url in window.open. (#11146)
				sOpenUrl = '';
			}

			// With Firefox only, we need to open a special preview page, so
			// anchors will work properly on it. (#9047)
			if ( CKEDITOR.env.gecko ) {
				window._cke_htmlToLoad = eventData.dataValue;
				sOpenUrl = CKEDITOR.getUrl( pluginPath + 'preview.html' );
			}

			var oWindow = window.open( sOpenUrl, null, 'toolbar=yes,location=no,status=yes,menubar=yes,scrollbars=yes,resizable=yes,width=' +
				iWidth + ',height=' + iHeight + ',left=' + iLeft );

			// For IE we want to assign whole js stored in ieLocation, but in case of
			// popup blocker activation oWindow variable will be null. (#11597)
			if ( CKEDITOR.env.ie && oWindow )
				oWindow.location = ieLocation;

			if ( !CKEDITOR.env.ie && !CKEDITOR.env.gecko ) {
				var doc = oWindow.document;
				doc.open();
				doc.write( eventData.dataValue );
				doc.close();
			}

			return true;
		}
	};

	var pluginName = 'sakaipreview';

	CKEDITOR.plugins.add( pluginName, {
		lang: 'en',
		icons: 'preview,preview-rtl',
		hidpi: true,
		init: function( editor ) {

			// Preview is not used for the inline creator.
			if ( editor.elementMode == CKEDITOR.ELEMENT_MODE_INLINE )
				return;

			pluginPath = this.path;

			editor.addCommand( pluginName, previewCmd );
			editor.ui.addButton && editor.ui.addButton( 'SakaiPreview', {
				label: editor.lang.sakaipreview.preview,
				command: pluginName,
				toolbar: 'document,40'
			} );
		}
	} );
} )();

/**
 * Event fired when executing `preview` command, which allows additional data manipulation.
 * With this event, the raw HTML content of the preview window to be displayed can be altered
 * or modified.
 *
 * @event contentPreview
 * @member CKEDITOR
 * @param {CKEDITOR.editor} editor This editor instance.
 * @param data
 * @param {String} data.dataValue The data that will go to the preview.
 */
